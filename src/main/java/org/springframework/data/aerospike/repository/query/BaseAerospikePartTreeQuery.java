/*
 * Copyright 2012-2019 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.aerospike.repository.query;

import org.springframework.beans.BeanUtils;
import org.springframework.data.aerospike.query.Qualifier;
import org.springframework.data.repository.query.ParameterAccessor;
import org.springframework.data.repository.query.ParametersParameterAccessor;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.QueryMethodEvaluationContextProvider;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.parser.AbstractQueryCreator;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Objects;

/**
 * @author Peter Milne
 * @author Jean Mercier
 * @author Igor Ermolenko
 */
public abstract class BaseAerospikePartTreeQuery implements RepositoryQuery {

    protected final QueryMethod queryMethod;
    protected final Class<?> entityClass;
    private final QueryMethodEvaluationContextProvider evaluationContextProvider;
    private final Class<? extends AbstractQueryCreator<?, ?>> queryCreator;

    protected BaseAerospikePartTreeQuery(QueryMethod queryMethod,
                                         QueryMethodEvaluationContextProvider evalContextProvider,
                                         Class<? extends AbstractQueryCreator<?, ?>> queryCreator) {
        this.queryMethod = queryMethod;
        this.evaluationContextProvider = evalContextProvider;
        this.queryCreator = queryCreator;
        this.entityClass = queryMethod.getEntityInformation().getJavaType();
    }

    @Override
    public QueryMethod getQueryMethod() {
        return queryMethod;
    }

    protected Query prepareQuery(Object[] parameters, ParametersParameterAccessor accessor) {
        PartTree tree = new PartTree(queryMethod.getName(), entityClass);
        Query baseQuery = createQuery(accessor, tree);

        AerospikeCriteria criteria = baseQuery.getAerospikeCriteria();
        Query query = new Query(criteria);

        if (accessor.getPageable().isPaged()) {
            query.setOffset(accessor.getPageable().getOffset());
            query.setRows(accessor.getPageable().getPageSize());
        } else {
            if (tree.isLimiting()) { // whether it contains "first"/"top"
                query.limit(tree.getMaxResults());
            } else {
                query.setOffset(-1);
                query.setRows(-1);
            }
        }

        query.setDistinct(tree.isDistinct());

        if (accessor.getSort().isSorted()) {
            query.setSort(accessor.getSort());
        } else {
            query.setSort(baseQuery.getSort());
        }

        if (query.getCriteria() instanceof SpelExpression spelExpression) {
            EvaluationContext context = this.evaluationContextProvider.getEvaluationContext(queryMethod.getParameters(),
                parameters);
            spelExpression.setEvaluationContext(context);
        }

        return query;
    }

    Class<?> getTargetClass(ParametersParameterAccessor accessor) {
        // Dynamic projection
        if (accessor.getParameters().hasDynamicProjection()) {
            return accessor.findDynamicProjection();
        }
        // DTO projection
        if (queryMethod.getReturnedObjectType() != queryMethod.getEntityInformation().getJavaType()) {
            return queryMethod.getReturnedObjectType();
        }
        // No projection - target class will be the entity class.
        return queryMethod.getEntityInformation().getJavaType();
    }

    public Query createQuery(ParametersParameterAccessor accessor, PartTree tree) {
        Constructor<? extends AbstractQueryCreator<?, ?>> constructor = ClassUtils
            .getConstructorIfAvailable(queryCreator, PartTree.class, ParameterAccessor.class);
        return (Query) BeanUtils.instantiateClass(constructor, tree, accessor).createQuery();
    }

    protected static boolean isIdQuery(AerospikeCriteria criteria) {
        return Objects.equals(criteria.getField(), "id");
    }

    protected static boolean hasIdQualifier(AerospikeCriteria criteria) {
        Object qualifiers = criteria.get("qualifiers");
        return qualifiers != null && qualifiers.getClass().isArray()
            && Arrays.stream((Qualifier[]) qualifiers).anyMatch(qualifier -> qualifier.getField().equals("id"));
    }

    protected static Qualifier[] excludeIdQualifier(Qualifier[] qualifiers) {
        return Arrays.stream(qualifiers).filter(qualifier -> !qualifier.getField().equals("id"))
            .toArray(Qualifier[]::new);
    }

    protected static Qualifier[] getQualifiers(AerospikeCriteria criteria) {
        if (criteria == null) {
            return null;
        } else if (criteria.getQualifiers() == null) {
            return new Qualifier[]{(criteria)};
        }
        return criteria.getQualifiers();
    }

    protected static Qualifier getIdQualifier(Qualifier[] qualifiers) {
        return Arrays.stream(qualifiers).filter(qualifier -> qualifier.getField().equals("id"))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Qualifier with 'id' field was not found"));
    }

    protected static Object getIdValue(Qualifier... qualifiers) {
        return Arrays.stream(qualifiers).filter(qualifier -> qualifier.getField().equals("id"))
            .map(qualifier -> qualifier.getValue1().getObject())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Value of 'id' field in a Qualifier was not found"));
    }

    protected Object runIdQuery(Class<?> sourceClass, Class<?> targetClass, Object ids, Qualifier... qualifiers) {
        Object result;
        if (ids == null) {
            throw new IllegalStateException("Parameters accessor value is null while parameters quantity is > 0");
        } else if (ids.getClass().isArray()) {
            result = findByIds(Arrays.stream(((Object[]) ids)).toList(), sourceClass, targetClass,
                qualifiers);
        } else if (ids instanceof Iterable<?>) {
            result = findByIds((Iterable<?>) ids, sourceClass, targetClass, qualifiers);
        } else {
            result = findById(ids, sourceClass, targetClass, qualifiers);
        }
        return result;
    }

    abstract Object findById(Object obj, Class<?> sourceClass, Class<?> targetClass, Qualifier... qualifiers);

    abstract Object findByIds(Iterable<?> iterable, Class<?> sourceClass, Class<?> targetClass,
                              Qualifier... qualifiers);
}
