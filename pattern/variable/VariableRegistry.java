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

package grakn.core.pattern.variable;

import grabl.tracing.client.GrablTracingThreadStatic;
import grakn.core.common.exception.GraknException;
import graql.lang.pattern.variable.BoundVariable;
import graql.lang.pattern.variable.ConceptVariable;
import graql.lang.pattern.variable.Reference;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static grabl.tracing.client.GrablTracingThreadStatic.traceOnThread;
import static grakn.common.collection.Collections.set;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static grakn.core.common.exception.ErrorMessage.Pattern.ANONYMOUS_CONCEPT_VARIABLE;
import static grakn.core.common.exception.ErrorMessage.Pattern.ANONYMOUS_TYPE_VARIABLE;
import static grakn.core.common.exception.ErrorMessage.Pattern.UNBOUNDED_CONCEPT_VARIABLE;
import static java.util.Collections.unmodifiableSet;

public class VariableRegistry {

    private static final String TRACE_PREFIX = "variableregistry.";

    private final VariableRegistry bounds;
    private final Map<Reference, TypeVariable> types;
    private final Map<Reference, ThingVariable> things;
    private final Set<ThingVariable> anonymous;

    public VariableRegistry(@Nullable final VariableRegistry bounds) {
        this.bounds = bounds;
        types = new HashMap<>();
        things = new HashMap<>();
        anonymous = new HashSet<>();
    }

    public static VariableRegistry createFromTypes(final List<graql.lang.pattern.variable.TypeVariable> variables) {
        try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "types")) {
            final VariableRegistry registry = new VariableRegistry(null);
            variables.forEach(registry::register);
            return registry;
        }
    }

    public static VariableRegistry createFromThings(final List<graql.lang.pattern.variable.ThingVariable<?>> variables) {
        try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "things")) {
            return createFromVariables(variables, null);
        }
    }

    public static VariableRegistry createFromVariables(final List<? extends BoundVariable> variables,
                                                       @Nullable final VariableRegistry bounds) {
        try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "variables")) {
            final List<ConceptVariable> unboundedVariables = new ArrayList<>();
            final VariableRegistry registry = new VariableRegistry(bounds);
            variables.forEach(graqlVar -> {
                if (graqlVar.isConcept()) unboundedVariables.add(graqlVar.asConcept());
                else registry.register(graqlVar);
            });
            unboundedVariables.forEach(registry::register);
            return registry;
        }
    }

    private Variable register(final graql.lang.pattern.variable.BoundVariable graqlVar) {
        if (graqlVar.isThing()) return register(graqlVar.asThing());
        else if (graqlVar.isType()) return register(graqlVar.asType());
        else if (graqlVar.isConcept()) return register(graqlVar.asConcept());
        else throw GraknException.of(ILLEGAL_STATE);
    }

    public Variable register(final ConceptVariable graqlVar) {
        if (graqlVar.reference().isAnonymous()) throw GraknException.of(ANONYMOUS_CONCEPT_VARIABLE);
        if (things.containsKey(graqlVar.reference())) {
            return things.get(graqlVar.reference()).constraintConcept(graqlVar.constraints(), this);
        } else if (types.containsKey(graqlVar.reference())) {
            return types.get(graqlVar.reference()).constrainConcept(graqlVar.constraints(), this);
        } else if (bounds != null && bounds.contains(graqlVar.reference())) {
            Reference.Referrable ref = graqlVar.reference().asReferrable();
            if (bounds.get(graqlVar.reference()).isThing()) {
                things.put(ref, new ThingVariable(Identifier.of(ref)));
                return things.get(ref).constraintConcept(graqlVar.constraints(), this);
            } else {
                types.put(ref, new TypeVariable(Identifier.of(ref)));
                return types.get(ref).constrainConcept(graqlVar.constraints(), this);
            }
        } else {
            throw GraknException.of(UNBOUNDED_CONCEPT_VARIABLE.message(graqlVar.reference()));
        }
    }

    public TypeVariable register(final graql.lang.pattern.variable.TypeVariable graqlVar) {
        if (graqlVar.reference().isAnonymous()) throw GraknException.of(ANONYMOUS_TYPE_VARIABLE);
        return types.computeIfAbsent(
                graqlVar.reference(), ref -> new TypeVariable(Identifier.of(ref.asReferrable()))
        ).constrainType(graqlVar.constraints(), this);
    }

    public ThingVariable register(final graql.lang.pattern.variable.ThingVariable<?> graqlVar) {
        final ThingVariable graknVar;
        if (graqlVar.reference().isAnonymous()) {
            graknVar = new ThingVariable(Identifier.of(graqlVar.reference().asAnonymous(), anonymous.size()));
            anonymous.add(graknVar);
        } else {
            graknVar = things.computeIfAbsent(graqlVar.reference(), r -> new ThingVariable(Identifier.of(r.asReferrable())));
        }
        return graknVar.constrainThing(graqlVar.constraints(), this);
    }

    public Set<TypeVariable> types() {
        return set(types.values());
    }

    public Set<ThingVariable> things() {
        return set(things.values(), anonymous);
    }

    public Set<Variable> variables() {
        final Set<Variable> output = new HashSet<>();
        output.addAll(types.values());
        output.addAll(things.values());
        output.addAll(anonymous);
        return unmodifiableSet(output);
    }

    public boolean contains(final Reference reference) {
        return things.containsKey(reference) || types.containsKey(reference);
    }

    public Variable get(final Reference reference) {
        if (things.containsKey(reference)) return things.get(reference);
        else return types.get(reference);
    }

    public Variable put(final Reference reference, final Variable variable) {
        if (variable.isType()) {
            things.remove(reference);
            return types.put(reference, variable.asType());
        } else if (variable.isThing()) {
            types.remove(reference);
            return things.put(reference, variable.asThing());
        } else throw GraknException.of(ILLEGAL_STATE);
    }
}
