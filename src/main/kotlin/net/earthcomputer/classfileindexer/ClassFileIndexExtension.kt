package net.earthcomputer.classfileindexer

import com.intellij.codeEditor.JavaEditorFileSwapper
import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.*
import com.intellij.util.indexing.impl.IndexStorage
import com.intellij.util.indexing.impl.storage.VfsAwareMapReduceIndex
import com.intellij.util.io.*
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.ClassReader
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import java.nio.file.Path

class ClassFileIndexExtension : FileBasedIndexExtension<String, Map<BinaryIndexKey, Map<String, Int>>>(),
    CustomImplementationFileBasedIndexExtension<String, Map<BinaryIndexKey, Map<String, Int>>> {
    override fun getName() = INDEX_ID

    override fun getIndexer() = DataIndexer<String, Map<BinaryIndexKey, Map<String, Int>>, FileContent> { content ->
        val bytes = content.content
        val cv = IndexerClassVisitor()
        ClassReader(bytes).accept(cv, ClassReader.SKIP_FRAMES)
        cv.index as Map<String, Map<BinaryIndexKey, Map<String, Int>>>
    }

    override fun getKeyDescriptor() = object : KeyDescriptor<String> {
        override fun getHashCode(value: String): Int = value.hashCode()

        override fun isEqual(val1: String, val2: String) = val1 == val2

        override fun save(out: DataOutput, value: String) {
            writeString(out, value)
        }

        override fun read(input: DataInput) = readString(input)
    }

    override fun getValueExternalizer() = object : DataExternalizer<Map<BinaryIndexKey, Map<String, Int>>> {
        override fun save(out: DataOutput, value: Map<BinaryIndexKey, Map<String, Int>>) {
            DataInputOutputUtil.writeINT(out, value.size)
            for ((key, counts) in value) {
                key.write(out, ::writeString)
                DataInputOutputUtil.writeINT(out, counts.size)
                for ((location, count) in counts) {
                    writeString(out, location)
                    DataInputOutputUtil.writeINT(out, count)
                }
            }
        }

        override fun read(input: DataInput): Map<BinaryIndexKey, Map<String, Int>> {
            val result = SmartMap<BinaryIndexKey, MutableMap<String, Int>>()
            repeat(DataInputOutputUtil.readINT(input)) {
                val key = BinaryIndexKey.read(input, ::readString)
                val counts = SmartMap<String, Int>()
                repeat(DataInputOutputUtil.readINT(input)) {
                    val location = readString(input)
                    val count = DataInputOutputUtil.readINT(input)
                    counts[location] = count
                }
                result[key] = counts
            }
            return result
        }
    }

    override fun getVersion() = 2

    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(JavaClassFileType.INSTANCE)

    override fun dependsOnFileContent() = true

    companion object {
        private val LOGGER = Logger.getInstance(ClassFileIndexExtension::class.java)
        val INDEX_ID = ID.create<String, Map<BinaryIndexKey, Map<String, Int>>>("classfileindexer.index")
    }

    private val enumeratorPath: Path = IndexInfrastructure.getIndexRootDir(INDEX_ID).toPath().resolve("classfileindexer.constpool")
    private var enumerator = createEnumerator()

    private fun createEnumerator(): PersistentStringEnumerator {
        return PersistentStringEnumerator(enumeratorPath, 1024 * 4, true, StorageLockContext(true))
    }

    private fun readString(input: DataInput): String {
        return enumerator.valueOf(DataInputOutputUtil.readINT(input))?.intern()
            ?: throw IOException("Invalid enumerated string")
    }

    private fun writeString(output: DataOutput, value: String) {
        DataInputOutputUtil.writeINT(output, enumerator.enumerate(value))
    }

    override fun createIndexImplementation(
        extension: FileBasedIndexExtension<String, Map<BinaryIndexKey, Map<String, Int>>>,
        storage: IndexStorage<String, Map<BinaryIndexKey, Map<String, Int>>>
    ) = object : VfsAwareMapReduceIndex<String, Map<BinaryIndexKey, Map<String, Int>>>(extension, storage) {
        override fun doClear() {
            super.doClear()
            IOUtil.closeSafe(LOGGER, enumerator)
            IOUtil.deleteAllFilesStartingWith(enumeratorPath.toFile())
            enumerator = createEnumerator()
        }

        override fun doFlush() {
            super.doFlush()
            enumerator.force()
        }

        override fun doDispose() {
            try {
                super.doDispose()
            } finally {
                IOUtil.closeSafe(LOGGER, enumerator)
            }
        }
    }
}
