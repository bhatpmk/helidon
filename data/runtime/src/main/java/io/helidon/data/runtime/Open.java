/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
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
package io.helidon.data.runtime;

/**
 * An {@link AutoCloseable} with close handlers, in the style of, and with similar semantics to those of, {@link
 * java.util.stream.BaseStream}, but without {@link Exception} restrictions and without any dependency on {@link
 * java.util.stream.BaseStream} itself, and suitable for use in non-streaming applications.
 *
 * <p>{@link Open} is a <dfn>contract</dfn> between a supplier and a consumer. The supplier builds an {@link Open}, and
 * <dfn>supplies</dfn> it to the consumer, thus <dfn>transferring ownership</dfn> to the consumer.</p>
 *
 * <p>Normally, if a consumer accepts a closeable resource, it should not close the closeable resource because it does
 * not know its provenance or who actually owns it. This contract establishes both the provenance and the explicit
 * transfer of ownership to the consumer, thus permitting (in fact requiring) the consumer to later {@linkplain #close()
 * close} it without issues.</p>
 *
 * <p>As a helpful example, a {@link java.util.stream.BaseStream} implementation would be an {@link Open}
 * implementation, if that were possible: the supplier of the {@link java.util.stream.BaseStream} arranges for any
 * resource cleanup to happen and transfers ownership of the {@link java.util.stream.BaseStream} to the caller, who is
 * expected to {@linkplain java.util.stream.BaseStream#close() close it} when it is done using it.</p>
 *
 * <p>Overrides are strongly encouraged to restrict the {@code throws} clause of their {@link #onClose(Runnable)}
 * implementations in much the same way that {@link AutoCloseable} encourages its implementations to restrict the {@code
 * throws} clause of their {@link #close()} implementations. The two {@code throws} clauses in question should be
 * symmetrical.</p>
 *
 * @see #onClose(Runnable)
 * @see java.util.stream.BaseStream#close()
 * @see java.util.stream.BaseStream#onClose(Runnable)
 */
public interface Open extends AutoCloseable {

    /**
     * Returns an eqivalent {@link Open} with an additional close handler.
     *
     * <p>Overrides are encouraged to override this method to throw a restricted set of {@link Exception}s, just like
     * overrides of {@link AutoCloseable#close()}.</p>
     *
     * @param closeHandler a close handler
     * @return an eqivalent {@link Open} with an additional close handler
     * @throws NullPointerException if {@code closeHandler} is {@code null}
     * @throws IllegalStateException if this {@link Open} implementation has already been {@linkplain #close() closed}
     * @throws Exception if there was a problem with registration; overrides should reduce this {@code throws} clause to
     * match that of their {@link #close()} implementations
     */
    Open onClose(Runnable closeHandler) throws Exception;

}
