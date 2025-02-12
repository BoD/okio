/*
 * Copyright (C) 2021 Square, Inc.
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
package okio.internal

import okio.ExperimentalFileSystem
import okio.FileMetadata
import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use

/**
 * Returns metadata of the file, directory, or object identified by [path].
 *
 * @throws IOException if [path] does not exist or its metadata cannot be read.
 */
@ExperimentalFileSystem
@Throws(IOException::class)
internal fun FileSystem.commonMetadata(path: Path): FileMetadata {
  return metadataOrNull(path) ?: throw FileNotFoundException("no such file: $path")
}

@ExperimentalFileSystem
@Throws(IOException::class)
internal fun FileSystem.commonExists(path: Path): Boolean {
  return metadataOrNull(path) != null
}

@ExperimentalFileSystem
@Throws(IOException::class)
internal fun FileSystem.commonCreateDirectories(dir: Path) {
  // Compute the sequence of directories to create.
  val directories = ArrayDeque<Path>()
  var path: Path? = dir
  while (path != null && !exists(path)) {
    directories.addFirst(path)
    path = path.parent
  }

  // Create them.
  for (toCreate in directories) {
    createDirectory(toCreate)
  }
}

@ExperimentalFileSystem
@Throws(IOException::class)
internal fun FileSystem.commonCopy(source: Path, target: Path) {
  source(source).use { bytesIn ->
    sink(target).buffer().use { bytesOut ->
      bytesOut.writeAll(bytesIn)
    }
  }
}

@ExperimentalFileSystem
@Throws(IOException::class)
internal fun FileSystem.commonDeleteRecursively(fileOrDirectory: Path) {
  val sequence = sequence {
    collectRecursively(
      fileSystem = this@commonDeleteRecursively,
      stack = ArrayDeque(),
      path = fileOrDirectory,
      followSymlinks = false,
      postorder = true
    )
  }
  for (toDelete in sequence) {
    delete(toDelete)
  }
}

@ExperimentalFileSystem
@Throws(IOException::class)
internal fun FileSystem.commonListRecursively(dir: Path, followSymlinks: Boolean): Sequence<Path> {
  return sequence {
    val stack = ArrayDeque<Path>()
    stack.addLast(dir)
    for (child in list(dir)) {
      collectRecursively(
        fileSystem = this@commonListRecursively,
        stack = stack,
        path = child,
        followSymlinks = followSymlinks,
        postorder = false
      )
    }
  }
}

@ExperimentalFileSystem
internal suspend fun SequenceScope<Path>.collectRecursively(
  fileSystem: FileSystem,
  stack: ArrayDeque<Path>,
  path: Path,
  followSymlinks: Boolean,
  postorder: Boolean
) {
  // For listRecursively, visit enclosing directory first.
  if (!postorder) {
    yield(path)
  }

  val children = fileSystem.listOrNull(path) ?: listOf()
  if (children.isNotEmpty()) {
    // Figure out if path is a symlink and detect symlink cycles.
    var symlinkPath = path
    var symlinkCount = 0
    while (true) {
      if (followSymlinks && symlinkPath in stack) throw IOException("symlink cycle at $path")
      symlinkPath = fileSystem.symlinkTarget(symlinkPath) ?: break
      symlinkCount++
    }

    // Recursively visit children.
    if (followSymlinks || symlinkCount == 0) {
      stack.addLast(symlinkPath)
      try {
        for (child in children) {
          collectRecursively(fileSystem, stack, child, followSymlinks, postorder)
        }
      } finally {
        stack.removeLast()
      }
    }
  }

  // For deleteRecursively, visit enclosing directory last.
  if (postorder) {
    yield(path)
  }
}

/** Returns null if [path] is not a directory or cannot be read. */
@ExperimentalFileSystem
@Throws(IOException::class)
private fun FileSystem.listOrNull(path: Path): List<Path>? {
  return try {
    list(path)
  } catch (_: IOException) {
    null
  }
}

/** Returns a resolved path to the symlink target, resolving it if necessary. */
@ExperimentalFileSystem
@Throws(IOException::class)
internal fun FileSystem.symlinkTarget(path: Path): Path? {
  val target = metadata(path).symlinkTarget ?: return null
  return path.parent!!.div(target)
}
