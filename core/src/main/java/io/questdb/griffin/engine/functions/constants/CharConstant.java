/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
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

package io.questdb.griffin.engine.functions.constants;

import io.questdb.cairo.sql.Record;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.engine.functions.CharFunction;
import io.questdb.std.str.Utf16Sink;
import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8Sink;
import io.questdb.std.str.Utf8String;

public class CharConstant extends CharFunction implements ConstantFunction {
    public static final CharConstant ZERO = new CharConstant((char) 0);
    private final Utf8String utf8Value;
    private final char value;

    public CharConstant(char value) {
        this.value = value;
        this.utf8Value = value != 0 ? new Utf8String(value) : null;
    }

    public static CharConstant newInstance(char value) {
        return value != 0 ? new CharConstant(value) : ZERO;
    }

    @Override
    public char getChar(Record rec) {
        return value;
    }

    @Override
    public void getVarchar(Record rec, Utf8Sink utf8Sink) {
        if (value != 0) {
            utf8Sink.put(utf8Value);
        }
    }

    @Override
    public Utf8Sequence getVarcharA(Record rec) {
        return utf8Value;
    }

    @Override
    public Utf8Sequence getVarcharB(Record rec) {
        return utf8Value;
    }

    @Override
    public void toPlan(PlanSink sink) {
        sink.val('\'').val(value).val('\'');
    }
}
