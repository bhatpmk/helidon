/*
 * Copyright (c) 2025 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import io.helidon.common.types.TypeName;
import io.helidon.service.registry.Service;

/**
 * Helidon Data Repository annotations and interfaces.
 */
public final class Data {

    private Data() {
        throw new UnsupportedOperationException("No instances of Data are allowed");
    }

    /**
     * Repository interface.
     * Data repository interface marked with this annotation will be processed by code generator.
     * This is a required repository annotation.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.CLASS)
    public @interface Repository {
    }

    /**
     * Repository persistence unit name.
     * <p>
     * This is an optional repository annotation.
     * <p>
     * When used, the persistence unit name will be used to lookup appropriate instance of configured
     * {@code data.persistence-units} to handle this repository.
     * This is useful when multiple databases are used from a single application.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.CLASS)
    public @interface PersistenceUnit {

        /**
         * Name of a named persistence unit.
         * When using configuration, this is expected under {@code data.persistence-units.provider-type}, where provider-type
         * is the provider of the persistence unit (such as {@code jakarta}).
         *
         * @return the name
         */
        String value();

        /**
         * Whether the named {@link io.helidon.data.Data.PersistenceUnit} is required.
         *
         * @return value of {@code true} when the {@link #value() named} {@link io.helidon.data.Data.PersistenceUnit} is required,
         *         {@code false} otherwise, to use the default configuration if a named one is not available
         */
        boolean required() default true;
    }

    /**
     * Provider used to implement the repository.
     * <p>
     * This is an optional repository annotation.
     * <p>
     * When used, code generation will be done only by the defined provider type.
     * This is useful when multiple providers are used from a single application.
     */
    @Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD})
    @Retention(RetentionPolicy.CLASS)
    public @interface Provider {
        /**
         * Type of the Helidon Data Provider that will handle this instance.
         *
         * @return provider type
         */
        String value();
    }

    /**
     * Qualifier used in generated code to reference which provider type to use when creating instances of repositories,
     * such as {@code eclipselink, jakarta, sql}.
     */
    @Service.Qualifier
    @Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD})
    public @interface ProviderType {
        /**
         * Type of this annotation (from Helidon Common Types).
         */
        TypeName TYPE = TypeName.create(ProviderType.class);

        /**
         * Type of the Helidon Data Support that will handle this instance.
         *
         * @return support type
         */
        String value();
    }

    /**
     * User supplied query.
     * <p>
     * Used in repository methods with query defined by annotation. This is the annotation to define the query.
     * Query language depends on {@code data.persistence-units.provider-type}, e.g. it's JPQL for {@code jakarta}
     * provider type.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface Query {
        /**
         * The query.
         *
         * @return the query string
         */
        String value();
    }

    /**
     * Result mapping from a provider result field to a model property.
     * <p>
     * This annotation is intended for compile-time repository processing. Providers may use it to generate mapper code
     * without relying on runtime reflection or implicit column/property naming.
     */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.SOURCE)
    @Repeatable(Maps.class)
    public @interface Map {
        /**
         * Source result field, such as a SQL column label.
         * <p>
         * This is an alias for {@link #source()}.
         *
         * @return source result field
         */
        String value() default "";

        /**
         * Source result field, such as a SQL column label.
         * <p>
         * This is an alias for {@link #value()}.
         *
         * @return source result field
         */
        String source() default "";

        /**
         * Target model property.
         *
         * @return target model property
         */
        String target();
    }

    /**
     * Container annotation for repeatable {@link Data.Map} declarations.
     */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Maps {
        /**
         * Mapping declarations.
         *
         * @return mapping declarations
         */
        Map[] value();
    }

    /**
     * Result aggregation key.
     * <p>
     * Providers may use this annotation when generating relationship reducers for joined result sets. When omitted,
     * providers may use their documented key conventions, such as {@code id} for the root object and
     * {@code relation.id} for collection elements.
     */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.SOURCE)
    @Repeatable(Keys.class)
    public @interface Key {
        /**
         * Source result fields that make up the key.
         * <p>
         * This is an alias for {@link #source()}.
         *
         * @return source result fields
         */
        String[] value() default {};

        /**
         * Source result fields that make up the key.
         * <p>
         * This is an alias for {@link #value()}.
         *
         * @return source result fields
         */
        String[] source() default {};

        /**
         * Target aggregate path. An empty value identifies the root aggregate.
         *
         * @return target aggregate path
         */
        String target() default "";
    }

    /**
     * Container annotation for repeatable {@link Data.Key} declarations.
     */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Keys {
        /**
         * Key declarations.
         *
         * @return key declarations
         */
        Key[] value();
    }

    /**
     * Declarative result mapper contract.
     * <p>
     * Providers may generate mapper implementations from this annotation and companion {@link Data.Map} declarations.
     * The annotated type describes mapping metadata only; applications are not expected to implement JDBC row-reading logic.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mapper {
        /**
         * Target model type created by the generated mapper.
         *
         * @return target model type
         */
        Class<?> target();
    }

    /**
     * Selects a result mapper for a repository method.
     * <p>
     * The selected type may be a declarative {@link Data.Mapper} contract whose implementation is generated at build time.
     * Providers may also use this annotation later for explicit mapper service extension points.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface MapWith {
        /**
         * Mapper contract or mapper service type.
         *
         * @return mapper type
         */
        Class<?> value();
    }

    /**
     * Selects a result reducer for a repository method.
     * <p>
     * The selected type may be a declarative {@link Data.Mapper} contract whose reducer implementation is generated at
     * build time. Providers may also use this annotation later for explicit reducer service extension points.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReduceWith {
        /**
         * Reducer contract or reducer service type.
         *
         * @return reducer type
         */
        Class<?> value();
    }

    /**
     * Generated keys requested by a repository method.
     * <p>
     * This annotation is provider-neutral. JDBC providers may translate the supplied column names to
     * {@link java.sql.Connection#prepareStatement(String, String[])} generated-key handling.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface GeneratedKeys {
        /**
         * Generated-key column names. When empty, the provider default generated-key behavior is used.
         *
         * @return generated-key column names
         */
        String[] value() default {};
    }

    /**
     * Data repository interface for basic entity operations.
     *
     * @param <E>  the entity type
     * @param <ID> the identifier type
     */
    public interface BasicRepository<E, ID> extends GenericRepository<E, ID> {

        /**
         * Save provided entity.
         * This method will update existing record or insert a new record if record does not exist in the database.
         *
         * @param entity the entity to persist, shall not be {@code null}
         * @param <T>    type of the entity
         * @return persisted entity. Never returns {@code null}
         * @throws io.helidon.data.DataException if the entity is {@code null} or the operation has failed
         */
        <T extends E> T save(T entity);

        /**
         * Save all provided entities.
         * This method will update existing record or insert a new record if record does not exist in the database.
         *
         * @param entities the entities to persist, shall not be {@code null}
         * @param <T>      type of the entity
         * @return persisted entities, never returns {@code null}
         * @throws io.helidon.data.DataException if the entities are {@code null} or the operation has failed
         */
        <T extends E> Iterable<T> saveAll(Iterable<T> entities);

        /**
         * Find entity by ID (primary key) value.
         *
         * @param id the ID of the entity to search for, shall not be {@code null}
         * @return the entity with the given ID or {@code Optional#empty()} if no such entity was found, never returns
         *         {@code null}
         * @throws io.helidon.data.DataException if the ID is {@code null} or the operation has failed
         */
        Optional<E> findById(ID id);

        /**
         * Check whether entity with given ID (primary key) exists.
         *
         * @param id the ID of the entity to search for, shall not be {@code null}
         * @return value of {@code true} if an entity with the given ID exists or {@code false} otherwise
         * @throws io.helidon.data.DataException if the ID is {@code null} or the operation has failed
         */
        boolean existsById(ID id);

        /**
         * Return all entities of the {@code E} type.
         * This method will return all records from related database table, so it should be used carefully
         * to avoid performance issues.
         *
         * @return all entities found, never returns {@code null}
         * @throws io.helidon.data.DataException if the operation has failed
         */
        Stream<E> findAll();

        /**
         * Return the number of all entities of the {@code E} type.
         *
         * @return the number of all entities found
         * @throws io.helidon.data.DataException if the operation has failed
         */
        long count();

        /**
         * Delete the entity with the given ID (primary key).
         *
         * @param id ID of the entity to be deleted, shall not be {@code null}
         * @return the number of deleted entities
         * @throws io.helidon.data.DataException if the ID is {@code null} or the operation has failed
         */
        long deleteById(ID id);

        /**
         * Delete provided entity.
         *
         * @param entity the entity to delete, shall not be {@code null}
         * @throws io.helidon.data.DataException if the entity is {@code null} or the operation has failed
         */
        void delete(E entity);

        /**
         * Delete all provided entities.
         *
         * @param entities the entities to delete, shall not be {@code null}
         * @throws io.helidon.data.DataException if the entities are {@code null} or the operation has failed
         */
        void deleteAll(Iterable<? extends E> entities);

        /**
         * Delete all entities of the {@code E} type.
         * This method will delete all records from related database table, so it should be used carefully
         * to avoid unexpected loss of data.
         *
         * @return the number of deleted entities
         * @throws io.helidon.data.DataException if the operation has failed
         */
        long deleteAll();

    }

    /**
     * Data repository interface for CRUD entity operations.
     * CRUD entity operations are:<ul>
     * <li>Create</li>
     * <li>Read</li>
     * <li>Update</li>
     * <li>Delete</li></ul>
     *
     * @param <E>  the entity type
     * @param <ID> the identifier type
     */
    public interface CrudRepository<E, ID> extends BasicRepository<E, ID> {

        /**
         * Insert provided entity.
         * This method will insert a new record into the database. The operation will fail if the record
         * is already present in the database.
         *
         * @param entity the entity to persist, shall not be {@code null}
         * @param <T>    type of the entity
         * @return persisted entity, never returns {@code null}
         * @throws io.helidon.data.DataException if the entity is {@code null} or the operation has failed
         */
        <T extends E> T insert(T entity);

        /**
         * Insert all provided entities.
         * This method will insert a new record into the database. The operation will fail if the record
         * is already present in the database or entities are not unique.
         *
         * @param entities the entities to persist, shall not be {@code null}
         * @param <T>      type of the entity
         * @return persisted entity, never returns {@code null}
         * @throws io.helidon.data.DataException if the entity is {@code null} or the operation has failed
         */
        <T extends E> Iterable<T> insertAll(Iterable<T> entities);

        /**
         * Update provided entity.
         * This operation will fail if the record is not already present in the database.
         *
         * @param entity the entity to persist, shall not be {@code null}
         * @param <T>    type of the entity
         * @return updated entity, never returns {@code null}
         * @throws io.helidon.data.DataException if the entity is {@code null} or the operation has failed
         */
        <T extends E> T update(T entity);

        /**
         * Update all provided entities.
         * This operation will fail if the record is not already present in the database.
         *
         * @param entities the entities to persist, shall not be {@code null}
         * @param <T>      type of the entity
         * @return updated entities, never returns {@code null}
         * @throws io.helidon.data.DataException if the entities are {@code null} or the operation has failed
         */
        <T extends E> Iterable<T> updateAll(Iterable<T> entities);

    }

    /**
     * Data repository interface.
     * This is the parent interface of all data repositories. Any user data repository interface must be annotated
     * with the {@link Data.Repository} annotation and extend this {@link Data.GenericRepository} interface.
     *
     * @param <E>  the entity type
     * @param <ID> the identifier type
     */
    public interface GenericRepository<E, ID> {
    }

    /**
     * Data repository interface with persistence provider session support.
     * <p>
     * This interface provides access to persistence provider session. Life cycle of the session is managed
     * by the Helidon Data framework.
     * <p>
     * Implementing this interface makes repository class to depend on specific persistence session type.
     * Target persistence session type must match session type of the specific persistence provider, e.g.<ul>
     * <li>{@code EntityManager} for Jakarta Persistence</li>
     * <li>{@code ClientSession} for native EclipseLink</li>
     * </ul>
     *
     * @param <S> type of the persistence session, e.g. {@code EntityManager}
     */
    public interface SessionRepository<S> {

        /**
         * Execute task with persistence session.
         * <p>
         * Persistence session life cycle is managed by the Helidon Data framework and this session
         * is available only while this method is running. Supplied {@link Consumer} shall not pass
         * provided persistence session instance outside this method scope. Supplied {@link Consumer}
         * shall not close provided persistence session.
         *
         * @param task task to be executed, shall not be {@code null}
         * @throws RuntimeException when task execution failed, checked exceptions are not allowed
         *                          and must be all handled by the supplied {@link Consumer}
         */
        void run(Consumer<S> task);

        /**
         * Execute task with persistence session.
         * <p>
         * Persistence session life cycle is managed by the Helidon Data framework and this session
         * is available only while this method is running. Supplied {@link Function} shall not pass
         * provided persistence session instance outside this method scope. Supplied {@link Function}
         * shall not close provided persistence session.
         *
         * @param task task to be executed, shall not be {@code null}
         * @param <R>  task result type
         * @return task result
         * @throws RuntimeException when task execution failed, checked exceptions are not allowed
         *                          and must be all handled by the supplied {@link Function}
         */
        <R> R call(Function<S, R> task);

    }

    /**
     * A {@link GenericRepository} that supports pagination.
     *
     * @param <E>  the entity type
     * @param <ID> the identifier type
     */
    public interface PageableRepository<E, ID> extends GenericRepository<E, ID> {

        /**
         * Return {@link Page} with all entities of the {@code E} type.
         * This is pageable alternative of {@link BasicRepository#findAll}.
         *
         * @param pageable pageable query result as page with specified page number and size, shall not be {@code null}
         * @return all entities found, never returns {@code null}
         * @throws io.helidon.data.DataException if the operation has failed
         */
        Page<E> pages(PageRequest pageable);

        /**
         * Return {@link Slice} with all entities of the {@code E} type.
         * This is pageable alternative of {@link BasicRepository#findAll}.
         *
         * @param pageable pageable query result as page with specified page number and size, shall not be {@code null}
         * @return all entities found, never returns {@code null}
         * @throws io.helidon.data.DataException if the operation has failed
         */
        Slice<E> slices(PageRequest pageable);

    }
}
