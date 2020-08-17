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

package grakn.core.concept.type;

import grakn.core.common.exception.GraknException;
import grakn.core.concept.Concept;

import java.util.List;
import java.util.stream.Stream;

import static grakn.core.common.exception.ErrorMessage.TypeRead.INVALID_TYPE_CASTING;

public interface Type extends Concept {

    @Override
    default Type asType() { return this; }

    @Override
    byte[] getIID();

    Long count();

    boolean isRoot();

    void setLabel(String label);

    String getLabel();

    boolean isAbstract();

    Type getSupertype();

    Stream<? extends Type> getSupertypes();

    Stream<? extends Type> getSubtypes();

    List<GraknException> validate();

    default ThingType asThingType() {
        throw new GraknException(INVALID_TYPE_CASTING.message(ThingType.class.getCanonicalName()));
    }

    default EntityType asEntityType() {
        throw new GraknException(INVALID_TYPE_CASTING.message(EntityType.class.getCanonicalName()));
    }

    default AttributeType asAttributeType() {
        throw new GraknException(INVALID_TYPE_CASTING.message(AttributeType.class.getCanonicalName()));
    }

    default RelationType asRelationType() {
        throw new GraknException(INVALID_TYPE_CASTING.message(RelationType.class.getCanonicalName()));
    }

    default RoleType asRoleType() {
        throw new GraknException(INVALID_TYPE_CASTING.message(RoleType.class.getCanonicalName()));
    }
}
