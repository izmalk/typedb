/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.rocks;

import grakn.core.Grakn;
import grakn.core.common.concurrent.ManagedReadWriteLock;
import grakn.core.common.exception.GraknException;
import grakn.core.common.options.GraknOptions;
import grakn.core.concept.Concepts;
import grakn.core.graph.Graphs;
import grakn.core.graph.util.KeyGenerator;
import grakn.core.graph.util.Storage;
import grakn.core.query.Query;
import org.rocksdb.OptimisticTransactionOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.Transaction;
import org.rocksdb.WriteOptions;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import static grakn.core.common.collection.Bytes.bytesHavePrefix;
import static grakn.core.common.collection.Bytes.longToSortedBytes;
import static grakn.core.common.exception.Error.Transaction.DIRTY_DATA_WRITES;
import static grakn.core.common.exception.Error.Transaction.DIRTY_SCHEMA_WRITES;
import static grakn.core.common.exception.Error.Transaction.ILLEGAL_COMMIT;
import static grakn.core.common.exception.Error.Transaction.TRANSACTION_CLOSED;

class RocksTransaction implements Grakn.Transaction {

    private static final byte[] EMPTY_ARRAY = new byte[]{};
    private final RocksSession session;
    private final GraknOptions.Transaction options;
    private final OptimisticTransactionOptions optOptions;
    private final WriteOptions writeOptions;
    private final ReadOptions readOptions;
    private final Transaction rocksTransaction;
    private final Type type;
    private final CoreStorage storage;
    private final Graphs graph;
    private final Concepts concepts;
    private final Query query;
    private final AtomicBoolean isOpen;

    RocksTransaction(RocksSession session, Type type, GraknOptions.Transaction options) {
        this.type = type;
        this.session = session;
        this.options = options;
        this.options.parent(session.options());

        readOptions = new ReadOptions();
        writeOptions = new WriteOptions();
        optOptions = new OptimisticTransactionOptions().setSetSnapshot(true);
        rocksTransaction = session.rocks().beginTransaction(writeOptions, optOptions);
        readOptions.setSnapshot(rocksTransaction.getSnapshot());

        storage = new CoreStorage();
        graph = new Graphs(storage);
        concepts = new Concepts(graph);
        query = new Query(graph, concepts);

        isOpen = new AtomicBoolean(true);
    }

    Graphs graph() {
        return graph;
    }

    public CoreStorage storage() {
        if (!isOpen.get()) throw new GraknException(TRANSACTION_CLOSED);
        return storage;
    }

    @Override
    public Type type() {
        return type;
    }

    @Override
    public GraknOptions.Transaction options() {
        return options;
    }

    @Override
    public boolean isOpen() {
        return this.isOpen.get();
    }

    @Override
    public Query query() {
        if (!isOpen.get()) throw new GraknException(TRANSACTION_CLOSED);
        return query;
    }

    @Override
    public Concepts concepts() {
        if (!isOpen.get()) throw new GraknException(TRANSACTION_CLOSED);
        return concepts;
    }

    /**
     * Commits any writes captured in the transaction into storage.
     *
     * If the transaction was opened as a {@code READ} transaction, then this
     * operation will throw an exception. If this transaction has been committed,
     * it cannot be committed again. If it has not been committed, then it will
     * flush all changes in the graph into storage by calling {@code graph.commit()},
     * which may result in acquiring a lock on the storage to confirm that the data
     * will be committed into storage. The operation will then continue to commit
     * all the writes into RocksDB by calling {@code rocksTransaction.commit()}.
     * If the operation reaches this state, then the RocksDB commit was successful.
     * We then need let go of the transaction that this resources of hold.
     *
     * If a lock was acquired from calling {@code graph.commit()} then we should
     * let inform the graph by confirming whether the RocksDB commit was successful
     * or not.
     */
    @Override
    public void commit() {
        if (isOpen.compareAndSet(true, false)) {
            try {
                if (type.equals(Type.READ)) {
                    throw new GraknException(ILLEGAL_COMMIT);
                } else if (session.type().equals(Grakn.Session.Type.DATA) && graph.type().isModified()) {
                    throw new GraknException(DIRTY_SCHEMA_WRITES);
                } else if (session.type().equals(Grakn.Session.Type.SCHEMA) && graph.thing().isModified()) {
                    throw new GraknException(DIRTY_DATA_WRITES);
                }

                // We disable RocksDB indexing of uncommitted writes, as we're only about to write and never again reading
                // TODO: We should benchmark this
                rocksTransaction.disableIndexing();
                if (session.type().equals(Grakn.Session.Type.SCHEMA)) {
                    concepts.validateTypes();
                    graph.type().commit();
                } else if (session.type().equals(Grakn.Session.Type.DATA)) {
                    concepts.validateThings();
                    graph.thing().commit();
                } else {
                    assert false;
                }

                rocksTransaction.commit();
            } catch (RocksDBException e) {
                rollback();
                throw new GraknException(e);
            } finally {
                graph.clear();
                closeResources();
            }
        } else {
            throw new GraknException(TRANSACTION_CLOSED);
        }
    }

    @Override
    public void rollback() {
        try {
            graph.clear();
            rocksTransaction.rollback();
        } catch (RocksDBException e) {
            throw new GraknException(e);
        }
    }

    @Override
    public void close() {
        if (isOpen.compareAndSet(true, false)) {
            closeResources();
        }
    }

    private void closeResources() {
        storage.close();
        optOptions.close();
        writeOptions.close();
        readOptions.close();
        rocksTransaction.close();
        session.remove(this);
    }

    class CoreStorage implements Storage {

        private final ManagedReadWriteLock readWriteLock;
        private final Set<RocksIterator<?>> iterators;

        CoreStorage() {
            readWriteLock = new ManagedReadWriteLock();
            iterators = ConcurrentHashMap.newKeySet();
        }

        @Override
        public GraknOptions.Transaction options() {
            return options;
        }

        @Override
        public boolean isOpen() {
            return isOpen.get();
        }

        @Override
        public KeyGenerator keyGenerator() {
            return session.keyGenerator();
        }

        @Override
        public byte[] get(byte[] key) {
            try {
                if (type.isWrite()) readWriteLock.lockRead();
                return rocksTransaction.get(readOptions, key);
            } catch (RocksDBException | InterruptedException e) {
                throw new GraknException(e);
            } finally {
                if (type.isWrite()) readWriteLock.unlockRead();
            }
        }

        @Override
        public byte[] getLastKey(byte[] prefix) {
            byte[] upperBound = Arrays.copyOf(prefix, prefix.length);
            upperBound[upperBound.length - 1] = (byte) (upperBound[upperBound.length - 1] + 1);
            assert upperBound[upperBound.length - 1] != Byte.MIN_VALUE;

            try (org.rocksdb.RocksIterator iterator = newRocksIterator()) {
                iterator.seekForPrev(upperBound);
                if (bytesHavePrefix(iterator.key(), prefix)) return iterator.key();
                else return null;
            }
        }

        @Override
        public void delete(byte[] key) {
            try {
                readWriteLock.lockWrite();
                rocksTransaction.delete(key);
            } catch (RocksDBException | InterruptedException e) {
                throw new GraknException(e);
            } finally {
                readWriteLock.unlockWrite();
            }
        }

        @Override
        public void put(byte[] key) {
            put(key, EMPTY_ARRAY);
        }

        @Override
        public void put(byte[] key, byte[] value) {
            try {
                readWriteLock.lockWrite();
                rocksTransaction.put(key, value);
            } catch (RocksDBException | InterruptedException e) {
                throw new GraknException(e);
            } finally {
                readWriteLock.unlockWrite();
            }
        }

        @Override
        public void putUntracked(byte[] key) {
            putUntracked(key, EMPTY_ARRAY);
        }

        @Override
        public void putUntracked(byte[] key, byte[] value) {
            try {
                readWriteLock.lockWrite();
                rocksTransaction.putUntracked(key, value);
            } catch (RocksDBException | InterruptedException e) {
                throw new GraknException(e);
            } finally {
                readWriteLock.unlockWrite();
            }
        }

        @Override
        public void mergeUntracked(byte[] key, long increment) {
            try {
                readWriteLock.lockWrite();
                rocksTransaction.mergeUntracked(key, longToSortedBytes(increment));
            } catch (RocksDBException | InterruptedException e) {
                throw new GraknException(e);
            } finally {
                readWriteLock.unlockWrite();
            }
        }

        @Override
        public <G> Iterator<G> iterate(byte[] key, BiFunction<byte[], byte[], G> constructor) {
            RocksIterator<G> iterator = new RocksIterator<>(this, key, constructor);
            iterators.add(iterator);
            return iterator;
        }

        org.rocksdb.RocksIterator newRocksIterator() {
            return rocksTransaction.getIterator(readOptions);
        }

        void remove(RocksIterator<?> iterator) {
            iterators.remove(iterator);
        }

        void close() {
            iterators.parallelStream().forEach(RocksIterator::close);
        }
    }
}
