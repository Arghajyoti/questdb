/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.std.str;

/**
 * A specialization of {@link DirectString} that does not add new methods, but provides additional
 * guarantees about the stability of the pointer returned by {@link DirectString#ptr()} method.
 * <p>
 * Indicates that a pointer returned by {@link DirectString#ptr()} method is stable during a query execution.
 * Stable is defined as:
 * - the pointer remains valid for the duration of the query execution
 * - the sequence of bytes pointed to by the pointer does not change during the query execution
 * <p>
 * Note: this class should be only used for direct {@link CharSequence}s. For {@link Utf8Sequence}s
 * we have special {@link Utf8Sequence#isStable()} method.
 */
public class StableDirectString extends DirectString {
}
