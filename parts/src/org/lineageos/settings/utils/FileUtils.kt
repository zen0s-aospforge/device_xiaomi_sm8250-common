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

package org.lineageos.settings.utils

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Utility class for file operations with improved null safety and modern Kotlin features.
 */
object FileUtils {
    private const val TAG = "FileUtils"
    private const val BUFFER_SIZE = 8192

    /**
     * Reads the first line of text from the given file.
     * Uses modern NIO.2 APIs with proper resource management.
     *
     * @param fileName the path to the file to read
     * @return the first line of the file, or null if file doesn't exist or can't be read
     */
    fun readOneLine(fileName: String?): String? {
        if (fileName.isNullOrBlank()) {
            Log.w(TAG, "Invalid filename provided")
            return null
        }

        return try {
            Files.lines(Paths.get(fileName), StandardCharsets.UTF_8)
                .use { lines ->
                    lines.findFirst().orElse(null)
                }
        } catch (e: IOException) {
            Log.e(TAG, "Could not read from file $fileName", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied reading file $fileName", e)
            null
        }
    }

    /**
     * Writes the given value as a line to the given file.
     * Uses modern NIO.2 APIs with proper encoding.
     *
     * @param fileName the path to the file to write
     * @param value the value to write
     * @return true on success, false on failure
     */
    fun writeLine(fileName: String?, value: String?): Boolean {
        if (fileName.isNullOrBlank()) {
            Log.w(TAG, "Invalid filename provided")
            return false
        }

        if (value == null) {
            Log.w(TAG, "Null value provided for writing")
            return false
        }

        return try {
            File(fileName).writeText(value + System.lineSeparator(), StandardCharsets.UTF_8)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Could not write to file $fileName", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied writing to file $fileName", e)
            false
        }
    }

    /**
     * Checks whether the given file exists.
     *
     * @param fileName the path to check
     * @return true if the file exists, false otherwise
     */
    fun fileExists(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        return try {
            Files.exists(Paths.get(fileName))
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied checking existence of $fileName", e)
            false
        }
    }

    /**
     * Checks whether the given file is readable.
     *
     * @param fileName the path to check
     * @return true if the file is readable, false otherwise
     */
    fun isFileReadable(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        return try {
            Files.isReadable(Paths.get(fileName))
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied checking readability of $fileName", e)
            false
        }
    }

    /**
     * Checks whether the given file is writable.
     *
     * @param fileName the path to check
     * @return true if the file is writable, false otherwise
     */
    fun isFileWritable(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        return try {
            Files.isWritable(Paths.get(fileName))
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied checking writability of $fileName", e)
            false
        }
    }

    /**
     * Deletes the given file or directory.
     *
     * @param fileName the path to delete
     * @return true if the delete was successful, false otherwise
     */
    fun delete(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) {
            Log.w(TAG, "Invalid filename provided for deletion")
            return false
        }

        return try {
            Files.deleteIfExists(Paths.get(fileName))
        } catch (e: IOException) {
            Log.e(TAG, "Could not delete file $fileName", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied deleting file $fileName", e)
            false
        }
    }

    /**
     * Renames/moves a file from source to destination.
     *
     * @param srcPath the source file path
     * @param dstPath the destination file path
     * @return true if the rename was successful, false otherwise
     */
    fun rename(srcPath: String?, dstPath: String?): Boolean {
        if (srcPath.isNullOrBlank() || dstPath.isNullOrBlank()) {
            Log.w(TAG, "Invalid paths provided for rename: src=$srcPath, dst=$dstPath")
            return false
        }

        return try {
            Files.move(Paths.get(srcPath), Paths.get(dstPath))
            true
        } catch (e: IOException) {
            Log.e(TAG, "Could not rename $srcPath to $dstPath", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied renaming $srcPath to $dstPath", e)
            false
        }
    }

    /**
     * Reads all lines from a file.
     *
     * @param fileName the path to the file
     * @return list of all lines, or empty list if file doesn't exist or can't be read
     */
    fun readAllLines(fileName: String?): List<String> {
        if (fileName.isNullOrBlank()) {
            Log.w(TAG, "Invalid filename provided")
            return emptyList()
        }

        return try {
            Files.readAllLines(Paths.get(fileName), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            Log.e(TAG, "Could not read all lines from file $fileName", e)
            emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied reading file $fileName", e)
            emptyList()
        }
    }

    /**
     * Gets file size in bytes.
     *
     * @param fileName the path to the file
     * @return file size in bytes, or -1 if file doesn't exist or can't be accessed
     */
    fun getFileSize(fileName: String?): Long {
        if (fileName.isNullOrBlank()) return -1L

        return try {
            Files.size(Paths.get(fileName))
        } catch (e: IOException) {
            Log.e(TAG, "Could not get size of file $fileName", e)
            -1L
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied getting size of $fileName", e)
            -1L
        }
    }

    /**
     * Creates parent directories for a file path if they don't exist.
     *
     * @param fileName the file path
     * @return true if directories were created successfully or already exist
     */
    fun createParentDirectories(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false

        return try {
            val path = Paths.get(fileName)
            val parent = path.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Could not create parent directories for $fileName", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied creating directories for $fileName", e)
            false
        }
    }
}

// Extension functions for additional convenience
fun String.readFirstLine(): String? = FileUtils.readOneLine(this)

fun String.writeLine(value: String): Boolean = FileUtils.writeLine(this, value)

fun String.fileExists(): Boolean = FileUtils.fileExists(this)

fun String.isReadable(): Boolean = FileUtils.isFileReadable(this)

fun String.isWritable(): Boolean = FileUtils.isFileWritable(this)

fun String.deleteFile(): Boolean = FileUtils.delete(this)

fun String.getFileSize(): Long = FileUtils.getFileSize(this)
