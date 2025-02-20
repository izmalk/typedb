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

import com.google.common.collect.Lists;
import com.vaticle.typedb.core.common.diagnostics.Diagnostics;
import com.vaticle.typedb.core.common.exception.TypeDBException;
import com.vaticle.typedb.core.common.iterator.FunctionalIterator;
import com.vaticle.typedb.core.common.parameters.Arguments;
import com.vaticle.typedb.core.common.parameters.Label;
import com.vaticle.typedb.core.common.parameters.Options;
import com.vaticle.typedb.core.concept.Concept;
import com.vaticle.typedb.core.concept.ConceptManager;
import com.vaticle.typedb.core.concept.answer.ConceptMap;
import com.vaticle.typedb.core.concept.thing.Relation;
import com.vaticle.typedb.core.concept.thing.Thing;
import com.vaticle.typedb.core.concept.type.AttributeType;
import com.vaticle.typedb.core.concept.type.RelationType;
import com.vaticle.typedb.core.concept.type.RoleType;
import com.vaticle.typedb.core.concept.type.ThingType;
import com.vaticle.typedb.core.concept.type.Type;
import com.vaticle.typedb.core.database.CoreDatabaseManager;
import com.vaticle.typedb.core.database.CoreSession;
import com.vaticle.typedb.core.database.CoreTransaction;
import com.vaticle.typedb.core.logic.LogicManager;
import com.vaticle.typedb.core.logic.Rule;
import com.vaticle.typedb.core.pattern.Conjunction;
import com.vaticle.typedb.core.test.integration.util.Util;
import com.vaticle.typedb.core.traversal.common.Identifier.Variable;
import com.vaticle.typeql.lang.TypeQL;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.vaticle.typedb.common.collection.Collections.map;
import static com.vaticle.typedb.common.collection.Collections.pair;
import static com.vaticle.typedb.common.collection.Collections.set;
import static com.vaticle.typedb.core.common.collection.Bytes.MB;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static com.vaticle.typedb.core.common.iterator.Iterators.iterate;
import static com.vaticle.typedb.core.logic.resolvable.Util.createRule;
import static com.vaticle.typedb.core.logic.resolvable.Util.getStringMapping;
import static com.vaticle.typedb.core.logic.resolvable.Util.resolvedConjunction;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnifyRelationConcludableTest {

    private static final Path dataDir = Paths.get(System.getProperty("user.dir")).resolve("unify-relation-test");
    private static final Path logDir = dataDir.resolve("logs");
    private static final Options.Database options = new Options.Database().dataDir(dataDir).reasonerDebuggerDir(logDir)
            .storageDataCacheSize(MB).storageIndexCacheSize(MB);
    private static final String database = "unify-relation-test";
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

        try (CoreSession schemaSession = databaseMgr.session(database, Arguments.Session.Type.SCHEMA)) {
            try (CoreTransaction tx = schemaSession.transaction(Arguments.Transaction.Type.WRITE)) {
                tx.query().define(TypeQL.parseQuery(
                        "define\n" +
                                "person sub entity,\n" +
                                "  owns first-name,\n" +
                                "  owns last-name,\n" +
                                "  owns age,\n" +
                                "  plays employment:employee,\n" +
                                "  plays employment:employer,\n" +
                                "  plays employment:employee-recommender,\n" +
                                "  plays friendship:friend;\n" +
                                "\n" +
                                "restricted-entity sub entity,\n" +
                                "  plays part-time-employment:restriction;\n" +
                                "student sub person,\n" +
                                "  plays part-time-employment:part-time-employee,\n" +
                                "  plays part-time-employment:part-time-employer,\n" +
                                "  plays part-time-employment:part-time-employee-recommender;\n" +
                                "\n" +
                                "student-driver sub student,\n" +
                                "  plays part-time-driving:night-shift-driver,\n" +
                                "  plays part-time-driving:day-shift-driver;\n" +
                                "organisation sub entity,\n" +
                                "  plays employment:employer,\n" +
                                "  plays employment:employee,\n" +
                                "  plays employment:employee-recommender;\n" +
                                "part-time-organisation sub organisation,\n" +
                                "  plays part-time-employment:part-time-employer,\n" +
                                "  plays part-time-employment:part-time-employee,\n" +
                                "  plays part-time-employment:part-time-employee-recommender;\n" +
                                "driving-hire sub part-time-organisation,\n" +
                                "  plays part-time-driving:taxi,\n" +
                                "  plays part-time-driving:night-shift-driver,\n" +
                                "  plays part-time-driving:day-shift-driver;\n" +
                                "\n" +
                                "employment sub relation,\n" +
                                "  relates employer,\n" +
                                "  relates employee,\n" +
                                "  relates contractor,\n" +
                                "  relates employee-recommender;\n" +
                                "\n" +
                                "part-time-employment sub employment,\n" +
                                "  relates part-time-employer as employer,\n" +
                                "  relates part-time-employee as employee,\n" +
                                "  relates part-time-employee-recommender as employee-recommender,\n" +
                                "  relates restriction;\n" +
                                "\n " +
                                "part-time-driving sub part-time-employment,\n" +
                                "  relates night-shift-driver as part-time-employee,\n" +
                                "  relates day-shift-driver as part-time-employee,\n" +
                                "  relates taxi as part-time-employer;\n" +
                                "friendship sub relation,\n" +
                                "    relates friend;\n" +
                                "name sub attribute, value string, abstract;\n" +
                                "first-name sub name;\n" +
                                "last-name sub name;\n" +
                                "age sub attribute, value long;"
                ).asDefine());
                tx.commit();
            }
        }

        try (CoreSession dataSession = databaseMgr.session(database, Arguments.Session.Type.DATA)) {
            try (CoreTransaction tx = dataSession.transaction(Arguments.Transaction.Type.WRITE)) {
                tx.query().insert(TypeQL.parseQuery(
                                "insert " +
                                        "(taxi: $x, night-shift-driver: $y) isa part-time-driving; " +
                                        "(part-time-employer: $x, part-time-employee: $y, part-time-employee-recommender: $z) isa part-time-employment; " +
                                        // note duplicate RP, needed to satisfy one of the child queries
                                        "(taxi: $x, night-shift-driver: $x, part-time-employee-recommender: $z) isa part-time-driving; " +
                                        "$x isa driving-hire;" +
                                        "$y isa driving-hire;" +
                                        "$z isa driving-hire;"
                        ).asInsert()
                );
                tx.commit();
            }
        }
        session = databaseMgr.session(database, Arguments.Session.Type.SCHEMA);
    }

    @AfterClass
    public static void tearDown() {
        session.close();
        databaseMgr.close();
    }

    @Before
    public void setUpTransaction() {
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
        assert type != null : "Cannot find type " + label;
        if (type.isEntityType()) return type.asEntityType().create();
        else if (type.isRelationType()) return type.asRelationType().create();
        else if (type.isAttributeType()) {
            AttributeType atype = type.asAttributeType();
            if (atype.isString()) return type.asAttributeType().asString().put("john");
            else if (atype.isLong()) return type.asAttributeType().asLong().put(10L);
        }
        throw TypeDBException.of(ILLEGAL_STATE);
    }

    private Type type(Label label) {
        if (label.scope().isPresent()) {
            return conceptMgr.getRelationType(label.scope().get()).getRelates(label.name());
        } else {
            return conceptMgr.getThingType(label.name());
        }
    }

    private Set<Label> typeHierarchy(String type) {
        return conceptMgr.getThingType(type).getSubtypes()
                .map(Type::getLabel).toSet();
    }

    private Set<Label> roleHierarchy(String roleType, String typeScope) {
        return type(Label.of(roleType, typeScope))
                .getSubtypes()
                .map(Type::getLabel)
                .toSet();
    }

    private void addRolePlayer(Relation relation, String role, Thing player) {
        RelationType relationType = relation.getType();
        RoleType roleType = relationType.getRelates(role);
        assert roleType != null : "Role type " + role + " does not exist in relation type " + relation.getType().getLabel();
        relation.addPlayer(roleType, player);
    }

    @Test
    public void relation_and_player_unifies_rule_relation_exact() {
        Unifier unifier = uniqueUnifier(
                "{ $r (employee: $y) isa employment; }",
                rule(
                        " (employee: $x) isa employment",
                        "{ $x isa person; }")
        );
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$r", set("$_0"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(
                typeHierarchy("employment"),
                unifier.requirements().isaExplicit().get(Variable.namedConcept("r")));
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(
                roleHierarchy("employee", "employment"),
                unifier.requirements().types().get(Variable.label("employee", "employment")));
        assertEquals(2, unifier.requirements().types().size());
        assertEquals(0, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        // code below tests unifier applied to an answer that is 1) satisfiable, 2) non-satisfiable
        Relation employment = instanceOf("employment").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        Map<Variable, Concept> concepts = map(
                pair(Variable.anon(0), employment),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.label("employment"), employment.getType()),
                pair(Variable.label("employee", "employment"), employment.getType().getRelates("employee"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        ConceptMap unifiedAnswer = unified.first().get();
        assertEquals(2, unifiedAnswer.concepts().size());
        assertEquals(employment, unifiedAnswer.getConcept("r"));
        assertEquals(person, unifiedAnswer.getConcept("y"));

        // filter out invalid types
        Relation friendship = instanceOf("friendship").asRelation();
        person = instanceOf("person");
        addRolePlayer(friendship, "friend", person);
        concepts = map(
                pair(Variable.anon(0), friendship),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.label("employment"), friendship.getType()),
                pair(Variable.label("employee", "employment"), friendship.getType().getRelates("friend"))
        );
        unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

    @Test
    public void relation_type_and_player_unifies_rule_relation_exact() {
        Unifier unifier = uniqueUnifier(
                "{ (employee: $y) isa $rel; }",
                rule(
                        "(employee: $x) isa employment",
                        "{ $x isa person; }")
        );
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$rel", set("employment")),
                pair("$_0", set("$_0"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(
                roleHierarchy("employee", "employment"),
                unifier.requirements().types().get(Variable.label("employee", "relation")));
        assertEquals(2, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(0, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Relation employment = instanceOf("employment").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        Map<Variable, Concept> concepts = map(
                pair(Variable.anon(0), employment),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.label("employment"), employment.getType()),
                pair(Variable.label("employee", "employment"), employment.getType().getRelates("employee"))
        );
        Set<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map())).toSet();
        assertFalse(unified.isEmpty());
        assertTrue(iterate(unified).allMatch(ans -> ans.concepts().size() == 3));
        assertTrue(iterate(unified).anyMatch(ans -> employment.getType().equals(ans.getConcept("rel")) &&
                person.equals(ans.getConcept("y")) &&
                employment.equals(ans.get(Variable.anon(0)))
        ));

        // filter out invalid types
        Relation friendship = instanceOf("friendship").asRelation();
        addRolePlayer(friendship, "friend", person);
        concepts = map(
                pair(Variable.anon(0), friendship),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.label("employment"), friendship.getType()),
                pair(Variable.label("employee", "employment"), friendship.getType().getRelates("friend"))
        );
        assertFalse(unifier.unUnify(concepts, new Unifier.Requirements.Instance(map())).hasNext());
    }

    @Test
    public void relation_role_unifies_rule_relation_exact() {
        Unifier unifier = uniqueUnifier(
                "{ ($role: $y) isa employment; }",
                rule(
                        " (employee: $x) isa employment ",
                        "{ $x isa person; }")
        );
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$role", set("employment:employee")),
                pair("$_0", set("$_0"))
        );
        assertEquals(expected, result);

        // test requirement
        assertEquals(
                typeHierarchy("employment"),
                unifier.requirements().isaExplicit().get(Variable.anon(0)));
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(0, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Relation employment = instanceOf("employment").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        Map<Variable, Concept> concepts = map(
                pair(Variable.anon(0), employment),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.label("employment"), employment.getType()),
                pair(Variable.label("employee", "employment"), employment.getType().getRelates("employee"))
        );
        Set<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map())).toSet();
        assertFalse(unified.isEmpty());
        assertTrue(iterate(unified).allMatch(ans -> ans.concepts().size() == 3));
        assertTrue(iterate(unified).anyMatch(ans -> employment.getType().getRelates("employee").equals(ans.getConcept("role")) &&
                person.equals(ans.getConcept("y")) &&
                employment.equals(ans.get(Variable.anon(0)))
        ));

        // filter out invalid types
        Relation friendship = instanceOf("friendship").asRelation();
        addRolePlayer(friendship, "friend", person);
        concepts = map(
                pair(Variable.anon(0), friendship),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.label("employment"), friendship.getType()),
                pair(Variable.label("employee", "employment"), friendship.getType().getRelates("friend"))
        );
        assertFalse(unifier.unUnify(concepts, new Unifier.Requirements.Instance(map())).hasNext());
    }

    @Test
    public void relation_without_isa_unifies_rule_relation() {
        Unifier unifier = uniqueUnifier(
                "{ (employee: $y); }",
                rule(
                        " (employee: $x) isa employment ",
                        "{ $x isa person; }")
        );
        Map<String, Set<String>> result = getStringMapping(unifier.mapping());
        Map<String, Set<String>> expected = map(
                pair("$y", set("$x")),
                pair("$_0", set("$_0"))
        );
        assertEquals(expected, result);

        // test requirements
        assertEquals(
                roleHierarchy("employee", "employment"),
                unifier.requirements().types().get(Variable.label("employee", "relation")));
        assertEquals(1, unifier.requirements().types().size());
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(0, unifier.requirements().predicates().size());
    }

    @Test
    public void relation_duplicate_players_unifies_rule_relation_distinct_players() {
        List<Unifier> unifiers = unifiers(
                "{ (employee: $p, employee: $p) isa employment; }",
                rule(
                        "($employee: $x, $employee: $y) isa $employment",
                        "{ $x isa person; $y isa person; $employment type employment;$employee type employment:employee; }")
        ).toList();
        Set<Map<String, Set<String>>> result = iterate(unifiers).map(u -> getStringMapping(u.mapping())).toSet();
        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x", "$y")),
                        pair("$_0", set("$_0"))
                )
        );

        assertEquals(expected, result);

        Unifier unifier = unifiers.get(0);
        // test requirements
        assertEquals(
                typeHierarchy("employment"),
                unifier.requirements().isaExplicit().get(Variable.anon(0)));
        assertEquals(2, unifier.requirements().isaExplicit().size());
        assertEquals(2, unifier.requirements().types().size());
        assertEquals(
                roleHierarchy("employee", "employment"),
                unifier.requirements().types().get(Variable.label("employee", "employment")));
        assertEquals(0, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Relation employment = instanceOf("employment").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        addRolePlayer(employment, "employee", person);
        Map<Variable, Concept> identifiedConcepts = map(
                pair(Variable.anon(0), employment),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.namedConcept("y"), person),
                pair(Variable.namedConcept("employment"), employment.getType()),
                pair(Variable.namedConcept("employee"), employment.getType().getRelates("employee"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        ConceptMap unifiedAnswer = unified.first().get();
        assertEquals(2, unifiedAnswer.concepts().size());
        assertEquals(person, unifiedAnswer.getConcept("p"));

        // filter out answers with differing role players that must be the same
        employment = instanceOf("employment").asRelation();
        person = instanceOf("person");
        Thing differentPerson = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        addRolePlayer(employment, "employee", differentPerson);
        identifiedConcepts = map(
                pair(Variable.anon(0), employment),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.namedConcept("y"), differentPerson),
                pair(Variable.namedConcept("employment"), employment.getType()),
                pair(Variable.namedConcept("employee"), employment.getType().getRelates("employee"))
        );
        unified = unifier.unUnify(identifiedConcepts, new Unifier.Requirements.Instance(map()));
        assertFalse(unified.hasNext());
    }

//TODO tests below do not test for requirements and unifier application to answers

    //[Single VARIABLE ROLE in parent]
    @Test
    public void relation_variables_one_to_many_unifiers() {
        String conjunction = "{ ($role: $p) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("three-people-are-employed",
                "{ $x isa person; $y isa person; $z isa person; }",
                "(employee: $x, employee: $y, employee: $z) isa employment", logicMgr);

        FunctionalIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x")),
                        pair("$role", set("employment:employee")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$y")),
                        pair("$role", set("employment:employee")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$z")),
                        pair("$role", set("employment:employee")),
                        pair("$_0", set("$_0"))
                )
        );
        assertEquals(expected, result);
    }

    //[REFLEXIVE rule, Fewer roleplayers in parent]
    @Test
    public void relation_variable_multiple_identical_unifiers() {
        String conjunction = "{ (employee: $p) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("the-same-person-is-employed-twice",
                "{ $x isa person; $y isa person; $employment type employment; $employee type employment:employee; }",
                "($employee: $x, $employee: $x) isa $employment", logicMgr);

        FunctionalIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x")),
                        pair("$_0", set("$_0"))
                )
        );
        assertEquals(expected, result);
    }

    //[many2many]
    @Test
    public void unify_relation_many_to_many() {
        String conjunction = "{ (employee: $p, employee: $q) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("three-people-are-employed",
                "{ $x isa person; $y isa person; $z isa person; }",
                "(employee: $x, employee: $y, employee: $z) isa employment", logicMgr);

        FunctionalIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x")),
                        pair("$q", set("$y")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$x")),
                        pair("$q", set("$z")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$y")),
                        pair("$q", set("$x")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$y")),
                        pair("$q", set("$z")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$z")),
                        pair("$q", set("$x")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$z")),
                        pair("$q", set("$y")),
                        pair("$_0", set("$_0"))
                )
        );
        assertEquals(expected, result);
    }

    //[many2many]
    @Test
    public void relation_player_role_unifies_rule_relation_repeated_variable_role() {
        String conjunction = "{ ($role: $p) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("two-people-are-employed",
                "{ $x isa person; $y isa person; $employment type employment; " +
                        "$employee type employment:employee; $employer type employment:employer; }",
                "($employee: $x, $employee: $y) isa $employment", logicMgr);

        FunctionalIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x")),
                        pair("$role", set("$employee")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$y")),
                        pair("$role", set("$employee")),
                        pair("$_0", set("$_0"))
                )
        );
        assertEquals(expected, result);
    }

    @Test
    public void relation_unifies_many_to_many_rule_relation_players() {
        String conjunction = "{ (employee: $p, employer: $p, employee: $q) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("two-people-are-employed-one-is-also-the-employer",
                "{ $x isa person; $y isa person; }",
                "(employee: $x, employer: $x, employee: $y) isa employment", logicMgr);

        List<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        Set<Map<String, Set<String>>> result = iterate(unifier).map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x")),
                        pair("$q", set("$y")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$x", "$y")),
                        pair("$q", set("$x")),
                        pair("$_0", set("$_0"))
                )
        );
        assertEquals(expected, result);
    }

    //[multiple VARIABLE ROLE, many2many]
    @Test
    public void relation_variable_role_unifies_many_to_many_rule_relation_roles() {
        String conjunction = "{ ($role1: $p, $role1: $q, $role2: $q) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("two-people-are-employed-one-is-also-the-employer",
                "{ $x isa person; $y isa person; }",
                "(employee: $x, employer: $x, employee: $y) isa employment", logicMgr);

        FunctionalIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x")),
                        pair("$q", set("$x", "$y")),
                        pair("$role1", set("employment:employee")),
                        pair("$role2", set("employment:employer")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$x")),
                        pair("$q", set("$x", "$y")),
                        pair("$role1", set("employment:employee", "employment:employer")),
                        pair("$role2", set("employment:employee")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$y")),
                        pair("$q", set("$x")),
                        pair("$role1", set("employment:employee", "employment:employer")),
                        pair("$role2", set("employment:employee")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$y")),
                        pair("$q", set("$x")),
                        pair("$role1", set("employment:employee")),
                        pair("$role2", set("employment:employer")),
                        pair("$_0", set("$_0"))
                )
        );
        assertEquals(expected, result);
    }

    //[multiple VARIABLE ROLE, many2many]
    @Test
    public void relation_variable_role_unifies_many_to_many_rule_relation_roles_2() {
        String conjunction = "{ ($role1: $p, $role2: $q, $role1: $p) isa employment; }";

        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("two-people-are-employed-one-is-also-the-employer",
                "{ $x isa person; $y isa person; }",
                "(employee: $x, employer: $x, employee: $y) isa employment", logicMgr);

        FunctionalIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x", "$y")),
                        pair("$q", set("$x")),
                        pair("$role1", set("employment:employee")),
                        pair("$role2", set("employment:employer")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$x", "$y")),
                        pair("$q", set("$x")),
                        pair("$role1", set("employment:employee", "employment:employer")),
                        pair("$role2", set("employment:employee")),
                        pair("$_0", set("$_0"))
                ),
                map(
                        pair("$p", set("$x")),
                        pair("$q", set("$y")),
                        pair("$role1", set("employment:employee", "employment:employer")),
                        pair("$role2", set("employment:employee")),
                        pair("$_0", set("$_0"))
                )
        );
        assertEquals(expected, result);
    }

    //[reflexive parent]
//TODO check answer satisfiability
    @Test
    public void relation_duplicate_roles_unifies_rule_relation_distinct_roles() {
        String conjunction = "{ (employee: $p, employee: $p) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("two-people-are-employed",
                "{ $x isa person; $y isa person; $employment type employment; $employee type employment:employee; }",
                "($employee: $x, $employee: $y) isa $employment", logicMgr);

        FunctionalIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x", "$y")),
                        pair("$_0", set("$_0"))
                )
        );
        assertEquals(expected, result);
    }

    //[reflexive child]
    @Test
    public void relation_distinct_roles_unifies_rule_relation_duplicate_roles() {
        String conjunction = "{ (employee: $p, employee: $q) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("a-person-is-employed-twice",
                "{ $x isa person; $employment type employment; $employee type employment:employee; }",
                "($employee: $x, $employee: $x) isa $employment", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        Set<Map<String, Set<String>>> result = iterate(unifiers).map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x")),
                        pair("$q", set("$x")),
                        pair("$_0", set("$_0"))
                )
        );
        assertEquals(expected, result);

        Unifier unifier = unifiers.get(0);
        // test requirements
        assertEquals(2, unifier.requirements().types().size());
        assertEquals(
                roleHierarchy("employee", "employment"),
                unifier.requirements().types().get(Variable.label("employee", "employment")));
        assertEquals(
                typeHierarchy("employment"),
                unifier.requirements().isaExplicit().get(Variable.anon(0)));
        assertEquals(3, unifier.requirements().isaExplicit().size());
        assertEquals(0, unifier.requirements().predicates().size());

        // test filter allows a valid answer
        Relation employment = instanceOf("employment").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(employment, "employee", person);
        addRolePlayer(employment, "employee", person);
        Map<Variable, Concept> concepts = map(
                pair(Variable.anon(0), employment),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.namedConcept("employment"), employment.getType()),
                pair(Variable.namedConcept("employee"), employment.getType().getRelates("employee"))
        );
        FunctionalIterator<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map()));
        assertTrue(unified.hasNext());
        ConceptMap unifiedAnswer = unified.first().get();
        assertEquals(3, unifiedAnswer.concepts().size());
        assertEquals(person, unifiedAnswer.getConcept("p"));
        assertEquals(person, unifiedAnswer.getConcept("q"));
        assertEquals(employment, unifiedAnswer.get(Variable.anon(0)));
    }

    //[reflexive parent, child]
    @Test
    public void relation_duplicate_roles_unifies_rule_relation_duplicate_roles() {
        String conjunction = "{ (employee: $p, employee: $p) isa employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("a-person-is-employed-twice",
                "{ $x isa person; $employment type employment; $employee type employment:employee; }",
                "($employee: $x, $employee: $x) isa $employment", logicMgr);

        FunctionalIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = set(
                map(
                        pair("$p", set("$x")),
                        pair("$_0", set("$_0"))
                )
        );
        assertEquals(expected, result);
    }

    @Test
    public void relation_more_players_than_rule_relation_fails_unify() {
        String relationQuery = "{ (part-time-employee: $r, part-time-employer: $p, restriction: $q) isa part-time-employment; }";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(relationQuery, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("one-employee-one-employer",
                "{ $x isa person; $y isa organisation; " +
                        "$employee type employment:employee; $employer type employment:employer; }",
                "($employee: $x, $employer: $y) isa employment", logicMgr);

        FunctionalIterator<Unifier> unifier = queryConcludable.unify(rule.conclusion(), conceptMgr);
        Set<Map<String, Set<String>>> result = unifier.map(u -> getStringMapping(u.mapping())).toSet();

        Set<Map<String, Set<String>>> expected = Collections.emptySet();
        assertEquals(expected, result);
    }

    @Test
    public void binaryRelationWithRoleHierarchy_ParentWithBaseRoles() {
        String relationQuery = "{ (employer: $x, employee: $y); }";
        String conclusion = "(part-time-employer: $u, part-time-employee: $v) isa part-time-employment";
        String conclusion2 = "(taxi: $u, night-shift-driver: $v) isa part-time-driving";

        verifyUnificationSucceeds(relationQuery, rule(conclusion, "{ $u isa part-time-organisation; $v isa student;}"));
        verifyUnificationSucceeds(relationQuery, rule(conclusion2, "{ $u isa driving-hire; $v isa student-driver;}"));
    }

    @Test
    public void binaryRelationWithRoleHierarchy_ParentWithSubRoles() {
        String relationQuery = "{ (part-time-employer: $x, part-time-employee: $y); }";
        String conclusion = "(part-time-employer: $u, part-time-employee: $v) isa part-time-employment";
        String conclusion2 = "(taxi: $u, night-shift-driver: $v) isa part-time-driving";
        String conclusion3 = "(taxi: $u, part-time-employee-recommender: $v) isa part-time-driving";
        String conclusion4 = "(employer: $u, employee: $v) isa employment";

        verifyUnificationSucceeds(relationQuery, rule(conclusion, "{$u isa part-time-organisation; $v isa student;}"));
        verifyUnificationSucceeds(relationQuery, rule(conclusion2, "{$u isa driving-hire; $v isa student-driver;}"));
        nonExistentUnifier(relationQuery, rule(conclusion3, "{$u isa driving-hire; $v isa student;}"));
        nonExistentUnifier(relationQuery, rule(conclusion4, "{$u isa part-time-organisation; $v isa person;}"));
    }

    @Test
    public void ternaryRelationWithRoleHierarchy_ParentWithBaseRoles() {
        String relationQuery = "{ (employer: $x, employee: $y, employee-recommender: $z); }";
        String conclusion = "(taxi: $u, night-shift-driver: $v, part-time-employee-recommender: $q) isa part-time-driving";
        String conclusion2 = "(part-time-employer: $u, part-time-employee: $v, part-time-employee-recommender: $q) isa part-time-employment";
        String conclusion3 = "(part-time-employer: $u, part-time-employer: $v, part-time-employee-recommender: $q) isa part-time-employment";

        verifyUnificationSucceeds(relationQuery, rule(conclusion, "{$u isa driving-hire; $v isa student-driver; $q isa student;}"));
        verifyUnificationSucceeds(relationQuery, rule(conclusion2, "{$u isa part-time-organisation; $v isa student; $q isa student;}"));
        nonExistentUnifier(relationQuery, rule(conclusion3, "{$u isa part-time-organisation; $v isa part-time-organisation; $q isa student;}"));
    }

    @Test
    public void ternaryRelationWithRoleHierarchy_ParentWithSubRoles() {
        String relationQuery = "{(part-time-employer: $x, part-time-employee: $y, part-time-employee-recommender: $z);}";
        String conclusion = "(employer: $u, employee: $v, employee-recommender: $q) isa employment";
        String conclusion2 = "(part-time-employer: $u, part-time-employee: $v, part-time-employee-recommender: $q) isa part-time-employment";
        String conclusion3 = "(taxi: $u, night-shift-driver: $v, part-time-employee-recommender: $q) isa part-time-driving";
        String conclusion4 = "(part-time-employer: $u, part-time-employer: $v, part-time-employee-recommender: $q) isa part-time-employment";

        nonExistentUnifier(relationQuery, rule(conclusion, "{$u isa organisation; $v isa person; $q isa person;}"));
        verifyUnificationSucceeds(relationQuery, rule(conclusion2, "{$u isa part-time-organisation; $v isa student; $q isa student;}"));
        verifyUnificationSucceeds(relationQuery, rule(conclusion3, "{$u isa driving-hire; $v isa student-driver; $q isa student;}"));
        nonExistentUnifier(relationQuery, rule(conclusion4, "{$u isa part-time-organisation; $v isa student; $q isa student;}"));
    }

    @Test
    public void ternaryRelationWithRoleHierarchy_ParentWithBaseRoles_childrenRepeatRolePlayers() {
        String relationQuery = "{ (employer: $x, employee: $y, employee-recommender: $z);}";
        String conclusion = "(employer: $u, employee: $u, employee-recommender: $q) isa employment";
        String conclusion2 = "(part-time-employer: $u, part-time-employee: $u, part-time-employee-recommender: $q) isa part-time-employment";
        String conclusion3 = "(part-time-employer: $u, part-time-employer: $u, part-time-employee-recommender: $q) isa part-time-employment";

        verifyUnificationSucceeds(relationQuery, rule(conclusion, "{$u isa student; $q isa student;}"));
        verifyUnificationSucceeds(relationQuery, rule(conclusion2, "{$u isa student; $q isa student;}"));
        nonExistentUnifier(relationQuery, rule(conclusion3, "{$u isa student; $q isa student;}"));
    }

    @Test
    public void ternaryRelationWithRoleHierarchy_ParentWithBaseRoles_parentRepeatRolePlayers() {
        String relationQuery = "{ (employer: $x, employee: $x, employee-recommender: $y);}";
        String conclusion = "(employer: $u, employee: $v, employee-recommender: $q) isa employment";
        String conclusion2 = "(part-time-employer: $u, part-time-employee: $v, part-time-employee-recommender: $q) isa part-time-employment";
        String conclusion3 = "(part-time-employer: $u, part-time-employer: $v, part-time-employee-recommender: $q) isa part-time-employment";

        verifyUnificationSucceeds(relationQuery, rule(conclusion, "{$u isa student; $v isa student; $q isa student;}"));
        verifyUnificationSucceeds(relationQuery, rule(conclusion2, "{$u isa student; $v isa student; $q isa student;}"));
        nonExistentUnifier(relationQuery, rule(conclusion3, "{$u isa student; $v isa student; $q isa student;}"));
    }

    @Test
    public void binaryRelationWithRoleHierarchy_ParentHasFewerRelationPlayers() {
        String parent = "{ (part-time-employer: $x) isa employment; }";
        String parent2 = "{ (part-time-employee: $y) isa employment; }";
        String conclusion = "(part-time-employer: $y, part-time-employee: $x) isa part-time-employment";

        verifyUnificationSucceeds(parent, rule(conclusion, "{$x isa student; $y isa student;}"));
        verifyUnificationSucceeds(parent2, rule(conclusion, "{$x isa student; $y isa student;}"));
    }

    @Test
    public void relations_with_known_role_player_types() {
        //NB: typed roleplayers have match (indirect) semantics, they specify the type plus its specialisations
        List<String> parents = Lists.newArrayList(
                "{(employer: $x, employee: $y); $x isa organisation;}",
                "{(employer: $x, employee: $y); $x isa part-time-organisation;}",
                "{(employer: $x, employee: $y); $x isa driving-hire;}",
                //3
                "{(employer: $x, employee: $y); $y isa person;}",
                "{(employer: $x, employee: $y); $y isa student;}",
                "{(employer: $x, employee: $y); $y isa student-driver;}",
                //6
                "{(employer: $x, employee: $y); $x isa person;$y isa student;}",
                "{(employer: $x, employee: $y); $x isa person;$y isa student-driver;}",
                "{(employer: $x, employee: $y); $x isa person;$y isa driving-hire;}",
                //9
                "{(employer: $x, employee: $y); $x isa student;$y isa student-driver;}",
                "{(employer: $x, employee: $y); $x isa student;$y isa part-time-organisation;}",
                "{(employer: $x, employee: $y); $x isa student;$y isa driving-hire;}",
                //12
                "{(employer: $x, employee: $y); $x isa student-driver;$y isa driving-hire;}",
                "{(employer: $x, employee: $y); $x isa student-driver;$y isa student-driver;}"
        );
        List<String> conclusions = Lists.newArrayList(
                "(employer: $p, employee: $q) isa employment",
                "(employer: $p, employee: $p) isa employment",
                "(part-time-employer: $p, part-time-employee: $q) isa part-time-employment",
                "(taxi: $p, day-shift-driver: $q) isa part-time-driving"
        );
        verifyUnificationSucceedsFor(rule(conclusions.get(0), "{$p isa person; $q isa person;}"), parents, Lists.newArrayList(3, 4, 5, 6, 7, 9, 13));
        verifyUnificationSucceedsFor(rule(conclusions.get(0), "{$p isa student; $q isa student-driver;}"), parents, Lists.newArrayList(3, 4, 5, 6, 7, 9, 13));

        verifyUnificationSucceedsFor(rule(conclusions.get(1), "{$p isa person;}"), parents, Lists.newArrayList(3, 4, 5, 6, 7, 9, 13));
        verifyUnificationSucceedsFor(rule(conclusions.get(1), "{$p isa student;}"), parents, Lists.newArrayList(3, 4, 5, 6, 7, 9, 13));

        verifyUnificationSucceedsFor(rule(conclusions.get(2), "{$p isa part-time-organisation;$q isa student;}"), parents, Lists.newArrayList(0, 1, 2, 3, 4, 5));
        verifyUnificationSucceedsFor(rule(conclusions.get(2), "{$p isa student;$q isa student-driver;}"), parents, Lists.newArrayList(3, 4, 5, 6, 7, 9, 13));

        verifyUnificationSucceedsFor(rule(conclusions.get(3), "{$p isa driving-hire; $q isa student-driver;}"), parents, Lists.newArrayList(0, 1, 2, 3, 4, 5));
        verifyUnificationSucceedsFor(rule(conclusions.get(3), "{$p isa driving-hire; $q isa student-driver;}"), parents, Lists.newArrayList(0, 1, 2, 3, 4, 5));
    }

    @Test
    public void relations_reflexive() {
        List<String> parents = Lists.newArrayList(
                "{(employer: $x, employee: $x); $x isa person;}",
                "{(part-time-employer: $x, part-time-employee: $x);}",
                "{(taxi: $x, employee: $x); $x isa organisation;}",
                "{(taxi: $x, employee: $x); $x isa driving-hire;}"
        );
        List<String> conclusions = Lists.newArrayList(
                "(employer: $p, employee: $q) isa employment",
                "(employer: $p, employee: $p) isa employment",
                "(part-time-employer: $p, part-time-employee: $q) isa part-time-employment",
                "(taxi: $p, day-shift-driver: $q) isa part-time-driving"
        );
        verifyUnificationSucceedsFor(rule(conclusions.get(0), "{$p isa student; $q isa student-driver;}"), parents, Lists.newArrayList(0));
        verifyUnificationSucceedsFor(rule(conclusions.get(1), "{$p isa student;}"), parents, Lists.newArrayList(0));

        //NB: here even though types are disjoint, the rule is unifiable
        verifyUnificationSucceedsFor(rule(conclusions.get(2), "{$p isa part-time-organisation;$q isa student;}"), parents, Lists.newArrayList(1));

        verifyUnificationSucceedsFor(rule(conclusions.get(2), "{$p isa student;$q isa student-driver;}"), parents, Lists.newArrayList(0, 1));

        verifyUnificationSucceedsFor(rule(conclusions.get(3), "{$p isa driving-hire; $q isa student-driver;}"), parents, Lists.newArrayList(1));
        verifyUnificationSucceedsFor(rule(conclusions.get(3), "{$p isa driving-hire; $q isa driving-hire;}"), parents, Lists.newArrayList(1, 2, 3));
    }

    @Test
    public void unUnify_produces_cartesian_named_types() {
        String conjunction = "{$r ($role: $x) isa $rel;}";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("people-are-self-friends", "{ $x isa person; }",
                " (friend: $x) isa friendship ", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);

        // test filter allows a valid answer
        Relation friendship = instanceOf("friendship").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(friendship, "friend", person);
        Map<Variable, Concept> concepts = map(
                pair(Variable.anon(0), friendship),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.label("friendship"), friendship.getType()),
                pair(Variable.label("friend", "friendship"), friendship.getType().getRelates("friend"))
        );
        List<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map())).toList();
        assertEquals(6, unified.size());

        Set<Map<String, String>> expected = set(
                map(
                        pair("$rel", "friendship"),
                        pair("$role", "friendship:friend")
                ),
                map(
                        pair("$rel", "friendship"),
                        pair("$role", "relation:role")
                ),
                map(
                        pair("$rel", "relation"),
                        pair("$role", "friendship:friend")
                ),
                map(
                        pair("$rel", "relation"),
                        pair("$role", "relation:role")
                ),
                map(
                        pair("$rel", "thing"),
                        pair("$role", "friendship:friend")
                ),
                map(
                        pair("$rel", "thing"),
                        pair("$role", "relation:role")
                )
        );

        Set<Map<String, String>> actual = new HashSet<>();
        iterate(unified).forEachRemaining(answer -> {
            actual.add(map(
                    pair("$rel", answer.getConcept("rel").asType().getLabel().name()),
                    pair("$role", answer.getConcept("role").asType().getLabel().scopedName())
            ));
        });

        assertEquals(expected, actual);
    }

    @Test
    public void unUnify_produces_cartesian_named_types_only_for_unbound_vars() {
        String conjunction = "{$r ($role: $x) isa $rel;}";
        Set<Concludable> concludables = Concludable.create(resolvedConjunction(conjunction, logicMgr));
        Concludable.Relation queryConcludable = concludables.iterator().next().asRelation();

        Rule rule = createRule("people-are-self-friends", "{ $x isa person; }",
                " (friend: $x) isa friendship ", logicMgr);

        List<Unifier> unifiers = queryConcludable.unify(rule.conclusion(), conceptMgr).toList();
        assertEquals(1, unifiers.size());
        Unifier unifier = unifiers.get(0);

        // test filter allows a valid answer
        Relation friendship = instanceOf("friendship").asRelation();
        Thing person = instanceOf("person");
        addRolePlayer(friendship, "friend", person);
        Map<Variable, Concept> concepts = map(
                pair(Variable.anon(0), friendship),
                pair(Variable.namedConcept("x"), person),
                pair(Variable.label("friendship"), friendship.getType()),
                pair(Variable.label("friend", "friendship"), friendship.getType().getRelates("friend"))
        );
        List<ConceptMap> unified = unifier.unUnify(concepts, new Unifier.Requirements.Instance(map(
                pair(Variable.namedConcept("rel"), friendship.getType())
        ))).toList();
        assertEquals(2, unified.size());

        Set<Map<String, String>> expected = set(
                map(
                        pair("$rel", "friendship"),
                        pair("$role", "friendship:friend")
                ),
                map(
                        pair("$rel", "friendship"),
                        pair("$role", "relation:role")
                )
        );

        Set<Map<String, String>> actual = new HashSet<>();
        iterate(unified).forEachRemaining(answer -> {
            actual.add(map(
                    pair("$rel", answer.getConcept("rel").asType().getLabel().name()),
                    pair("$role", answer.getConcept("role").asType().getLabel().scopedName())
            ));
        });

        assertEquals(expected, actual);
    }

    private Rule rule(String conclusion, String conditions) {
        return createRule(UUID.randomUUID().toString(), conditions, conclusion, logicMgr);
    }

    private FunctionalIterator<Unifier> unifiers(String parent, Rule rule) {
        Conjunction parentConjunction = resolvedConjunction(parent, logicMgr);
        Concludable.Relation queryConcludable = Concludable.create(parentConjunction).stream()
                .filter(Concludable::isRelation)
                .map(Concludable::asRelation)
                .findFirst().orElse(null);
        return queryConcludable.unify(rule.conclusion(), conceptMgr);
    }

    private void nonExistentUnifier(String parent, Rule rule) {
        assertFalse(unifiers(parent, rule).hasNext());
    }

    private Unifier uniqueUnifier(String parent, Rule rule) {
        List<Unifier> unifiers = unifiers(parent, rule).toList();
        assertEquals(1, unifiers.size());
        return unifiers.iterator().next();
    }

    private void verifyUnificationSucceedsFor(Rule rule, List<String> parents, List<Integer> unifiableParents) {
        for (int parentIndex = 0; parentIndex < parents.size(); parentIndex++) {
            String parent = parents.get(parentIndex);
            assertEquals(
                    String.format("Unexpected unification outcome at index [%s]:\nconjunction: %s\nconclusion: %s\ncondition: %s\n",
                            parentIndex, parent, rule.conclusion(), rule.condition().disjunction().pattern()),
                    unifiableParents.contains(parentIndex), unifiers(parent, rule).hasNext()
            );
        }
    }

    private void verifyUnificationSucceeds(String parent, Rule rule) {
        Unifier unifier = uniqueUnifier(parent, rule);
        List<? extends ConceptMap> childAnswers = transaction.query().get(TypeQL.match(rule.getThenPreNormalised()).get()).toList();
        List<? extends ConceptMap> parentAnswers = transaction.query().get(TypeQL.match(TypeQL.parsePattern(parent)).get()).toList();
        assertFalse(childAnswers.isEmpty());
        assertFalse(parentAnswers.isEmpty());

        List<ConceptMap> unifiedAnswers = iterate(childAnswers)
                .flatMap(ans -> {
                    Map<Variable, Concept> labelledTypes = addRequiredLabeledTypes(ans, unifier);
                    Map<Variable, Concept> requiredRetrievableConcepts = addRequiredRetrievableConcepts(ans, unifier);
                    labelledTypes.putAll(requiredRetrievableConcepts);
                    //TODO if want to use with iids add instance requirements
                    FunctionalIterator<ConceptMap> unified = unifier.unUnify(labelledTypes,
                                    new Unifier.Requirements.Instance(map()))
                            .map(u -> {
                                Map<Variable.Retrievable, Concept> concepts = u.concepts().entrySet().stream()
                                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                                requiredRetrievableConcepts.forEach(concepts::remove);
                                return new ConceptMap(concepts);
                            });
                    return unified;
                }).toList();

        assertFalse(unifiedAnswers.isEmpty());
        assertTrue(parentAnswers.containsAll(unifiedAnswers));
    }

    Map<Variable, Concept> addRequiredLabeledTypes(ConceptMap ans, Unifier unifier) {
        Map<Variable, Concept> withLabeledTypes = new HashMap<>(ans.concepts());
        unifier.unifiedRequirements().types().forEach((var, labels) -> labels.forEach(label -> withLabeledTypes.put(var, type(label))));
        return withLabeledTypes;
    }

    Map<Variable, Concept> addRequiredRetrievableConcepts(ConceptMap ans, Unifier unifier) {
        //insert random concepts for any var in unifier that is not in conceptmap
        Iterator<? extends Thing> instances = iterate(conceptMgr.getRootThingType().getInstances());
        return unifier.reverseUnifier().keySet().stream()
                .filter(var -> !ans.contains(var.asRetrievable()))
                .collect(Collectors.toMap(var -> var, var -> {
                    if (unifier.unifiedRequirements().isaExplicit().containsKey(var.asRetrievable())) {
                        return conceptMgr.getThingType(unifier.unifiedRequirements().isaExplicit().get(var.asRetrievable()).iterator().next().name())
                                .getInstances().first().get();
                    } else {
                        return instances.next();
                    }
                }));
    }
}
