/*
 * Copyright (C) 2016 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xiaomi.settings.utils

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileNotFoundException
import java.io.FileWriter
import java.io.IOException

object FileUtils {

    private const val TAG = "FileUtils"

    /**
     * Writes the given value into the given file.
     *
     * @return true on success, false on failure
     */
    fun writeLine(fileName: String, value: String): Boolean {
        var writer: BufferedWriter? = null
        try {
            writer = BufferedWriter(FileWriter(fileName))
            writer.write(value)
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "No such file $fileName for writing", e)
            return false
        } catch (e: IOException) {
            Log.e(TAG, "Could not write to file $fileName", e)
            return false
        } finally {
            try {
                writer?.close()
            } catch (e: IOException) {
                // Ignored
            }
        }
        return true
    }

    /** Checks whether the given file is writable. */
    fun isFileWritable(fileName: String): Boolean =
        File(fileName).let { it.exists() && it.canWrite() }
}
