/*
 * Copyright (C) 2022 Vaticle
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.vaticle.typedb.core.logic.resolvable;

import com.vaticle.typedb.core.common.diagnostics.Diagnostics;
import com.vaticle.typedb.core.common.exception.TypeDBException;
import com.vaticle.typedb.core.common.iterator.FunctionalIterator;
import com.vaticle.typedb.core.common.parameters.Arguments;
import com.vaticle.typedb.core.common.parameters.Label;
import com.vaticle.typedb.core.common.parameters.Options.Database;
import com.vaticle.typedb.core.concept.Concept;
import com.vaticle.typedb.core.concept.ConceptManager;
import com.vaticle.typedb.core.concept.answer.ConceptMap;
import com.vaticle.typedb.core.concept.thing.Thing;
import com.vaticle.typedb.core.concept.type.AttributeType;
import com.vaticle.typedb.core.concept.type.ThingType;
import com.vaticle.typedb.core.database.CoreDatabaseManager;
import com.vaticle.typedb.core.database.CoreSession;
import com.vaticle.typedb.core.database.CoreTransaction;
import com.vaticle.typedb.core.logic.LogicManager;
import com.vaticle.typedb.core.logic.Rule;
import com.vaticle.typedb.core.test.integration.util.Util;
import com.vaticle.typedb.core.traversal.common.Identifier;
import com.vaticle.typeql.lang.TypeQL;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.vaticle.typedb.common.collection.Collections.map;
import static com.vaticle.typedb.common.collection.Collections.pair;
import static com.vaticle.typedb.common.collection.Collections.set;
import static com.vaticle.typedb.core.common.collection.Bytes.MB;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static com.vaticle.typedb.core.logic.resolvable.Util.createRule;
import static com.vaticle.typedb.core.logic.resolvable.Util.getStringMapping;
import static com.vaticle.typedb.core.logic.resolvable.Util.resolvedConjunction;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnifyHasConcludableTest {

    private static final Path dataDir = Paths.get(System.getProperty("user.dir")).resolve("unify-isa-test");
    private static final Path logDir = dataDir.resolve("logs");
    private static final Database options = new Database().dataDir(dataDir).reasonerDebuggerDir(logDir)
            .storageDataCacheSize(MB).storageIndexCacheSize(MB);
    private static final String database = "unify-isa-test";
    private static CoreDatabaseManager databaseMgr;
    private static CoreSession session;
    private static CoreTransaction transaction;
    private static ConceptManager conceptMgr;
    private static LogicManager logicMgr;

    @BeforeClass
    public static void setUp() throws IOException {
        Diagnostics.initialiseNoop();
        Util.resetDirectory(dataDir);
        databaseMgr = CoreDatabaseManager.open(options);
        databaseMgr.create(database);
        session = databaseMgr.session(database, Arguments.Session.Type.SCHEMA);
        try (CoreTransaction tx = session.transaction(Arguments.Transaction.Type.WRITE)) {
            tx.query().define(TypeQL.parseQuery("define " +
                    "person sub entity," +
                    "    owns first-name," +
                    "    owns last-name," +
                    "    owns age," +
                    "    plays employment:employee;" +
                    "company sub entity," +
                    "    plays employment:employer;" +
                    "employment sub relation," +
                    "    relates employee," +
                    "    relates employer;" +
                    "name sub attribute, value string, abstract;" +
                    "first-name sub name, owns first-name;" + // need a name with a name for one test
                    "last-name sub name;" +
                    "age sub attribute, value long;" +
                    "self-owning-attribute sub attribute, value long, owns self-owning-attribute;" +
                    "").asDefine());
            tx.commit();
        }
    }

    @AfterClass
    public static void tearDown() {
        session.close();
        databaseMgr.close();
    }

    @Before
    public void setupTransaction() {
        transaction = session.transaction(Arguments.Transaction.Type.WRITE);
        conceptMgr = transaction.concepts();
        logicMgr = transaction.logic();
    }

    @After
    public void tearDownTransaction() {
        transaction.close();
    }

    private Thing instanceOf(String label) {
        ThingType type = conceptMgr.getThingType(label);
        assert type != null;
        if (type.isEntityType()) return type.asEntityType().create();
        else if (type.isRelationType()) return type.asRelationType().create();
        else if (type.isAttributeType() && type.asAttributeType().isString())
            return type.asAttributeType().asString().put("john");
        else if (type.isAttributeType() && type.asAttributeType().isLong())
            return type.asAttributeType().asLong().put(10L);
        else throw TypeDBException.of(ILLEGAL_STATE);
    }

    private Thing instanceOf(String stringAttributeLabel, String stringValue) {
        AttributeType type = conceptMgr.getAttributeType(stringAttributeLabel);
        assert type != null;
        return type.asString().put(stringValue);
    }

    private Thing instanceOf(String longAttributeLabel, Long longValue) {
        AttributeType type = conceptMgr.getAttributeType(longAttributeLabel);
        assert type != null;
        return type.asLong().put(longValue);
    }

    //TODO: create more tests when type inference is working to test unifier pruning

    @Test
    public void has_attribute_exact_unifies_rule_has_exact() {
        String conjunction = "{ $y has name 'john'; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; }", "$x has first-name 'john'", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$_0", set("$_0"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(set(Label.of("first-name"), Label.of("last-name")), unifier.requirements().isaExplicit().get(Identifier.Variable.anon(0)));
        assertEquals(1, unifier.requirements().predicates().size());

        // test forward unification can reject an invalid partial answer
        ConceptMap partialAnswer = new ConceptMap(map(pair(Identifier.Variable.anon(0), instanceOf("age"))));
        assertFalse(unifier.unify(partialAnswer).isPresent());

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.anon(0), instanceOf("first-name", "john"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // filter out invalid type
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.anon(0), instanceOf("age"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());

        // filter out invalid value
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.anon(0), instanceOf("first-name", "bob"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

    @Test
    public void has_attribute_exact_unifies_rule_has_variable() {
        String conjunction = "{ $y has name 'john'; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; $a isa first-name; }", "$x has $a", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$_0", set("$a"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(set(Label.of("first-name"), Label.of("last-name")), unifier.requirements().isaExplicit().get(Identifier.Variable.anon(0)));
        assertEquals(1, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("a"), instanceOf("last-name", "john"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // filter out invalid type
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("a"), instanceOf("age"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());

        // filter out invalid value
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("a"), instanceOf("first-name", "bob"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

    @Ignore
    @Test
    public void has_attribute_exact_prunes_irrelevant_rules() {
        // TODO: implement a test for unifier pruning, will require type hinting
    }

    @Test
    public void has_attribute_variable_unifies_rule_has_exact() {
        String conjunction = "{ $y has $a; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; }", "$x has first-name \"john\"", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$a", set("$_0"))
        );
        assertEquals(expected, result);

        // test unifier allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.anon(0), instanceOf("first-name", "john"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(0, unifier.requirements().predicates().size());
    }

    @Test
    public void has_attribute_variable_unifies_rule_has_variable() {
        String conjunction = "{ $y has $b; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; $a isa first-name; }", "$x has $a", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$b", set("$a"))
        );
        assertEquals(expected, result);

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("a"), instanceOf("last-name", "john"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(0, unifier.requirements().predicates().size());
    }

    @Test
    public void has_attribute_typed_variable_unifies_rule_has_exact() {
        String conjunction = "{ $y has name $b; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; }", "$x has first-name \"john\"", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$b", set("$_0"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(set(Label.of("first-name"), Label.of("last-name")), unifier.requirements().isaExplicit().get(Identifier.Variable.namedConcept("b")));
        assertEquals(0, unifier.requirements().predicates().size());

        // test forward unification can reject an invalid partial answer
        ConceptMap partialAnswer = new ConceptMap(map(pair(Identifier.Variable.namedConcept("b"), instanceOf("age"))));
        assertFalse(unifier.unify(partialAnswer).isPresent());

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.anon(0), instanceOf("first-name", "john"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // filter out invalid type
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.anon(0), instanceOf("age"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

    @Test
    public void has_attribute_typed_variable_unifies_rule_has_variable() {
        String conjunction = "{ $y has name $b; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; $a isa first-name; }", "$x has $a", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$b", set("$a"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(set(Label.of("first-name"), Label.of("last-name")), unifier.requirements().isaExplicit().get(Identifier.Variable.namedConcept("b")));
        assertEquals(0, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("a"), instanceOf("first-name", "john"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // filter out invalid type
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("a"), instanceOf("age"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

    @Test
    public void has_many_to_one_unifier() {
        String conjunction = "{ $x has attribute $y; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $a isa self-owning-attribute; }", "$a has $a", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$x", set("$a")),
                pair("$y", set("$a"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(0, unifier.requirements().predicates().size());
    }

    @Test
    public void has_one_to_many_unifier() {
        String conjunction = "{ $b has attribute $b; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa self-owning-attribute; }", "$x has self-owning-attribute 5", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$b", set("$x", "$_0"))
        );
        assertEquals(expected, result);
        // test requirements of one-to-many using valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("self-owning-attribute")),
                pair(Identifier.Variable.anon(0), instanceOf("self-owning-attribute"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(1, unified.next().concepts().size());

        // test requirements of one-to-many using invalid answer
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.anon(0), instanceOf("age"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

    @Test
    public void has_all_equivalent_vars_unifier() {
        String conjunction = "{ $b has self-owning-attribute $b; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $a isa self-owning-attribute; }", "$a has $a", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$b", set("$a"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(1, unifier.requirements().isaExplicit().size());
        assertEquals(set(Label.of("self-owning-attribute")), unifier.requirements().isaExplicit().get(Identifier.Variable.namedConcept("b")));
        assertEquals(0, unifier.requirements().predicates().size());
    }

    @Test
    public void has_attribute_notequal_unifies_rule_has_exact() {
        String conjunction = "{ $y has name != 'john'; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; }", "$x has first-name 'jane'", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$_0", set("$_0"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(set(Label.of("first-name"), Label.of("last-name")), unifier.requirements().isaExplicit().get(Identifier.Variable.anon(0)));
        assertEquals(1, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.anon(0), instanceOf("first-name", "jane"))
        );

        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // filter out invalid value
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.anon(0), instanceOf("first-name", "john"))
        );

        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

    @Test
    public void has_attribute_notequal_unifies_rule_has_variable() {
        String conjunction = "{ $y has name != 'john'; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; $n isa first-name; }", "$x has $n", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$_0", set("$n"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(set(Label.of("first-name"), Label.of("last-name")), unifier.requirements().isaExplicit().get(Identifier.Variable.anon(0)));
        assertEquals(1, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("n"), instanceOf("last-name", "bob"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // filter out invalid type
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("n"), instanceOf("age"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());

        // filter out invalid value
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("n"), instanceOf("first-name", "john"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

    @Test
    public void has_attribute_LT_unifies_rule_has_var() {
        String conjunction = "{ $y has age < 20; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; $a isa age; }", "$x has $a", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$_0", set("$a"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(1, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("a"), instanceOf("age", 15L))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // filter out invalid value
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("a"), instanceOf("age", 25L))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

    @Test
    public void has_attribute_GT_unifies_rule_has_var() {
        String conjunction = "{ $y has age > 20; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; $a isa age; }", "$x has $a", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$_0", set("$a"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(1, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("a"), instanceOf("age", 25L))
        );

        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // filter out invalid value
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("a"), instanceOf("age", 15L))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

    @Test
    public void has_attribute_contains_unifies_rule_has_var() {
        String conjunction = "{ $y has name contains 'van'; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; $n isa last-name; }", "$x has $n", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$_0", set("$n"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(set(Label.of("first-name"), Label.of("last-name")), unifier.requirements().isaExplicit().get(Identifier.Variable.anon(0)));
        assertEquals(1, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("n"), instanceOf("last-name", "van Gogh"))
        );

        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // filter out invalid type
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("n"), instanceOf("age"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());

        // filter out invalid value
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("n"), instanceOf("first-name", "john"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }


    @Test
    public void has_attribute_like_unifies_rule_has_var() {
        String conjunction = "{ $y has name like '[a-z]+@[a-z]+\\.(com|net)'; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Has queryConcludable = concludables.iterator().next().asHas();

        Rule rule = createRule("has-rule", "{ $x isa person; $n isa last-name; }", "$x has $n", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$_0", set("$n"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(0, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(set(Label.of("first-name"), Label.of("last-name")), unifier.requirements().isaExplicit().get(Identifier.Variable.anon(0)));
        assertEquals(1, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Map<Identifier.Variable, Concept> concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("n"), instanceOf("last-name", "reasoner@vaticle.com"))
        );

        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        assertEquals(2, unified.next().concepts().size());

        // filter out invalid type
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("n"), instanceOf("age"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());

        // filter out invalid value
        concepts = map(
                pair(Identifier.Variable.namedConcept("x"), instanceOf("person")),
                pair(Identifier.Variable.namedConcept("n"), instanceOf("first-name", "reasoner@grakn.ai"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }
}
