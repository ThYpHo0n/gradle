/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.internal.snapshot.impl

import org.gradle.api.Action
import org.gradle.api.file.FileTree
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.internal.cache.StringInterner
import org.gradle.api.internal.file.FileTreeInternal
import org.gradle.api.internal.file.TestFiles
import org.gradle.api.internal.file.collections.DirectoryFileTree
import org.gradle.internal.file.FileType
import org.gradle.internal.hash.TestFileHasher
import org.gradle.internal.snapshot.DirectorySnapshot
import org.gradle.internal.snapshot.FileSystemLocationSnapshot
import org.gradle.internal.snapshot.FileSystemSnapshot
import org.gradle.internal.snapshot.FileSystemSnapshotVisitor
import org.gradle.internal.snapshot.RegularFileSnapshot
import org.gradle.internal.snapshot.WellKnownFileLocations
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Specification
import spock.lang.Unroll

class DefaultFileSystemSnapshotterTest extends Specification {
    @Rule TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()
    def fileHasher = new TestFileHasher()
    def fileSystemMirror = new DefaultFileSystemMirror(Stub(WellKnownFileLocations))
    def snapshotter = new DefaultFileSystemSnapshotter(fileHasher, new StringInterner(), TestFiles.fileSystem(), fileSystemMirror)

    def "fetches details of a file and caches the result"() {
        def f = tmpDir.createFile("f")

        expect:
        def snapshot = snapshotter.snapshot(f)
        snapshot.absolutePath == f.path
        snapshot.name == "f"
        snapshot.type == FileType.RegularFile
        snapshot.isContentAndMetadataUpToDate(new RegularFileSnapshot(f.path, f.absolutePath, fileHasher.hash(f), TestFiles.fileSystem().stat(f).lastModified))

        def snapshot2 = snapshotter.snapshot(f)
        snapshot2.is(snapshot)
    }

    def "fetches details of a directory and caches the result"() {
        def d = tmpDir.createDir("d")

        expect:
        def snapshot = snapshotter.snapshot(d)
        snapshot.absolutePath == d.path
        snapshot.name == "d"
        snapshot.type == FileType.Directory

        def snapshot2 = snapshotter.snapshot(d)
        snapshot2.is(snapshot)
    }

    def "fetches details of a missing file and caches the result"() {
        def f = tmpDir.file("f")

        expect:
        def snapshot = snapshotter.snapshot(f)
        snapshot.absolutePath == f.path
        snapshot.name == "f"
        snapshot.type == FileType.Missing

        def snapshot2 = snapshotter.snapshot(f)
        snapshot2.is(snapshot)
    }

    def "fetches details of a directory hierarchy and caches the result"() {
        def d = tmpDir.createDir("d")
        d.createFile("f1")
        d.createFile("d1/f2")
        d.createDir("d2")

        expect:
        def snapshot = snapshotter.snapshot(d)
        getSnapshotInfo(snapshot) == [d.path, 5]

        def snapshot2 = snapshotter.snapshot(d)
        snapshot2.is(snapshot)
    }

    def "fetches details of an empty directory and caches the result"() {
        def d = tmpDir.createDir("d")

        expect:
        def snapshot = snapshotter.snapshot(d)
        getSnapshotInfo(snapshot) == [d.absolutePath, 1]

        def snapshot2 = snapshotter.snapshot(d)
        snapshot2.is(snapshot)
    }

    def "fetches details of a directory tree with no patterns and caches the result"() {
        def d = tmpDir.createDir("d")
        d.createFile("f1")
        d.createFile("d1/f2")
        d.createDir("d2")
        def tree = dirTree(d)

        expect:
        def snapshot = snapshotter.snapshotDirectoryTree(tree)
        getSnapshotInfo(snapshot) == [d.path, 5]

        def snapshot2 = snapshotter.snapshotDirectoryTree(tree)
        snapshot2.is(snapshot)

        def snapshot3 = snapshotter.snapshotDirectoryTree(dirTree(d))
        snapshot3.is(snapshot)
    }

    def "fetches details of a directory tree with patterns patterns and does not cache the result"() {
        def d = tmpDir.createDir("d")
        d.createFile("f1")
        d.createFile("d1/f2")
        d.createFile("d1/f1")
        d.createDir("d2")
        d.createFile("d2/f1")
        d.createFile("d2/f2")
        def patterns = TestFiles.patternSetFactory.create()
        patterns.include "**/*1"
        def tree = TestFiles.directoryFileTreeFactory().create(d, patterns)

        expect:
        def snapshot = snapshotter.snapshotDirectoryTree(tree)
        getSnapshotInfo(snapshot) == [d.path, 6]

        def snapshot2 = snapshotter.snapshotDirectoryTree(tree)
        !snapshot2.is(snapshot)

        def snapshot3 = snapshotter.snapshotDirectoryTree(dirTree(d))
        !snapshot3.is(snapshot)
        getSnapshotInfo(snapshot3) == [d.path, 8]

        def snapshot4 = snapshotter.snapshotDirectoryTree(dirTree(d))
        !snapshot4.is(snapshot)
        snapshot4.is(snapshot3)
    }

    def "reuses cached unfiltered trees when looking for details of a filtered tree"() {
        given: "An existing snapshot"
        def d = tmpDir.createDir("d")
        d.createFile("f1")
        d.createFile("d1/f2")
        d.createFile("d1/f1")
        def unfilteredTree = dirTree(d)
        snapshotter.snapshotDirectoryTree(unfilteredTree)

        and: "A filtered tree over the same directory"
        def patterns = TestFiles.patternSetFactory.create()
        patterns.include "**/*1"
        DirectoryFileTree filteredTree = Mock(DirectoryFileTree) {
            getDir() >> d
            getPatterns() >> patterns
        }

        when:
        def snapshot = snapshotter.snapshotDirectoryTree(filteredTree)
        def relativePaths = [] as Set
        snapshot.accept(new FileSystemSnapshotVisitor() {
            private Deque<String> relativePath = new ArrayDeque<String>()
            private boolean seenRoot = false

            @Override
            boolean preVisitDirectory(DirectorySnapshot directorySnapshot) {
                if (!seenRoot) {
                    seenRoot = true
                } else {
                    relativePath.addLast(directorySnapshot.name)
                    relativePaths.add(relativePath.join("/"))
                }
                return true
            }

            @Override
            void visit(FileSystemLocationSnapshot fileSnapshot) {
                relativePath.addLast(fileSnapshot.name)
                relativePaths.add(relativePath.join("/"))
                relativePath.removeLast()
            }

            @Override
            void postVisitDirectory(DirectorySnapshot directorySnapshot) {
                if (relativePath.isEmpty()) {
                    seenRoot = false
                } else {
                    relativePath.removeLast()
                }
            }
        })

        then: "The filtered tree uses the cached state"
        relativePaths == ["d1", "d1/f1", "f1"] as Set
    }

    def "snapshots a non-existing directory"() {
        given:
        def d = tmpDir.file("dir")

        when:
        def snapshot = snapshotter.snapshotDirectoryTree(dirTree(d))

        then:
        getSnapshotInfo(snapshot) == [null, 0]
    }

    def "snapshots file as directory tree"() {
        given:
        def d = tmpDir.createFile("fileAsTree")

        when:
        def snapshot = snapshotter.snapshotDirectoryTree(dirTree(d))

        then:
        getSnapshotInfo(snapshot) == [null, 1]
        snapshot.accept(new FileSystemSnapshotVisitor() {
            @Override
            boolean preVisitDirectory(DirectorySnapshot directorySnapshot) {
                throw new UnsupportedOperationException()
            }

            @Override
            void visit(FileSystemLocationSnapshot fileSnapshot) {
                assert fileSnapshot.absolutePath == d.getAbsolutePath()
                assert fileSnapshot.name == d.name
            }

            @Override
            void postVisitDirectory(DirectorySnapshot directorySnapshot) {
                throw new UnsupportedOperationException()
            }
        })
    }

    @Unroll
    def "snapshots #description of #input"() {
        given:
        tmpDir.createFile("file")
        tmpDir.createDir("emptyDir")
        tmpDir.file("missing")
        def dir = tmpDir.createDir("dir")
        dir.file("file-in-dir").createFile()
        def dirInDir = dir.file("dir-in-dir")
        dirInDir.createDir()
        dirInDir.createFile("file-in-dir-in-dir")
        def fileOperations = TestFiles.fileOperations(tmpDir.getTestDirectory())
        def inputFile = tmpDir.file(input)
        def tree = fromCollection ? fileOperations.immutableFiles(inputFile).asFileTree : fileOperations.fileTree(inputFile)

        expect:
        snapshotAndWalkerIsConsistent(tree)

        where:
        input      | fromCollection
        "file"     | false
        "file"     | true
        "dir"      | false
        "dir"      | true
        "emptyDir" | false
        "emptyDir" | true
        "missing"  | false
        "missing"  | true
        description = fromCollection ? "tree from file collection" : "fileTree"
    }

    private void snapshotAndWalkerIsConsistent(FileTree fileTree) {
        def snapshottedFiles = [:]
        snapshotter.snapshot((FileTreeInternal) fileTree).find()?.accept(new RelativePathTrackingVisitor() {
            @Override
            void visit(String absolutePath, Deque<String> relativePath) {
                if (relativePath.size() == 1) {
                    if (new File(absolutePath).isFile()) {
                        snapshottedFiles.put(absolutePath, relativePath.join("/"))
                    }
                } else {
                    snapshottedFiles.put(absolutePath, relativePath.tail().join('/'))
                }
            }
        })
        def walkedFiles = [:]
        fileTree.visit(new Action<FileVisitDetails>() {
            @Override
            void execute(FileVisitDetails fileVisitDetails) {
                walkedFiles.put(fileVisitDetails.file.absolutePath, fileVisitDetails.path)
            }
        })

        assert snapshottedFiles == walkedFiles
    }

    private static DirectoryFileTree dirTree(File dir) {
        TestFiles.directoryFileTreeFactory().create(dir)
    }

    private static List getSnapshotInfo(FileSystemSnapshot tree) {
        String rootPath = null
        int count = 0
        tree.accept(new FileSystemSnapshotVisitor() {
            @Override
            boolean preVisitDirectory(DirectorySnapshot directorySnapshot) {
                if (rootPath == null) {
                    rootPath = directorySnapshot.absolutePath
                }
                count++
                return true
            }

            @Override
            void visit(FileSystemLocationSnapshot fileSnapshot) {
                count++
            }

            @Override
            void postVisitDirectory(DirectorySnapshot directorySnapshot) {
            }
        })
        return [rootPath, count]
    }
}
