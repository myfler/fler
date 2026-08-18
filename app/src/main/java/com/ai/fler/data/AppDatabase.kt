package com.ai.fler.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.ai.fler.data.dao.AddressMappingDao
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.AsmBlockDao
import com.ai.fler.data.dao.DartCallGraphDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.DartObjectDao
import com.ai.fler.data.dao.EnumMapDao
import com.ai.fler.data.dao.HookScriptDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.McpToolStatDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.data.entity.AddressMapping
import com.ai.fler.data.entity.Analysis
import com.ai.fler.data.entity.AsmBlock
import com.ai.fler.data.entity.DartCallEdge
import com.ai.fler.data.entity.DartClass
import com.ai.fler.data.entity.DartMethod
import com.ai.fler.data.entity.DartObject
import com.ai.fler.data.entity.EnumMap
import com.ai.fler.data.entity.HookScript
import com.ai.fler.data.entity.Library
import com.ai.fler.data.entity.McpToolStat
import com.ai.fler.data.entity.PpEntry
import com.ai.fler.data.entity.Project

/**
 * fler 应用数据库。
 *
 * 基于 Room 的 SQLite 数据库，存储项目分析相关的所有数据。
 * 包含 7 个实体和对应的 DAO。
 */
@Database(
    entities = [
        Project::class,
        Analysis::class,
        DartClass::class,
        DartMethod::class,
        PpEntry::class,
        Library::class,
        AddressMapping::class,
        DartCallEdge::class,
        McpToolStat::class,
        HookScript::class,
        DartObject::class,
        EnumMap::class,
        AsmBlock::class
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun dartClassDao(): DartClassDao
    abstract fun dartMethodDao(): DartMethodDao
    abstract fun ppEntryDao(): PpEntryDao
    abstract fun libraryDao(): LibraryDao
    abstract fun addressMappingDao(): AddressMappingDao

    /** Dart 调用图边 DAO（真实交叉引用）。 */
    abstract fun dartCallGraphDao(): DartCallGraphDao

    /** MCP 工具调用统计 DAO。 */
    abstract fun mcpToolStatDao(): McpToolStatDao

    /** Hook 脚本 DAO（Frida 落地脚本增删改查）。 */
    abstract fun hookScriptDao(): HookScriptDao

    /** 对象池对象索引 DAO（引擎 objs.txt 轻量索引）。 */
    abstract fun dartObjectDao(): DartObjectDao

    /** 枚举索引映射 DAO（引擎 enum_map 表）。 */
    abstract fun enumMapDao(): EnumMapDao

    /** ASM 反汇编块 DAO（Blutter asm 完整产物）。 */
    abstract fun asmBlockDao(): AsmBlockDao

    /**
     * 级联删除项目及其所有关联数据。
     *
     * SQLite 默认不开启外键约束（Room 也不开），因此即使实体上声明了
     * `ForeignKey(onDelete = CASCADE)` 也不会自动触发。必须在应用层显式级联。
     *
     * 删除顺序（从叶子到根）：
     * 1. pp_entries / dart_methods / dart_classes / libraries —— 通过 analysis_id 关联
     * 2. analyses —— 通过 project_id 关联
     * 3. address_mappings —— 通过 project_id 关联（无 ForeignKey 声明）
     * 4. projects —— 根表
     *
     * 用 @Transaction 保证原子性：中途失败则全部回滚。
     *
     * @param projectId 要删除的项目 ID
     * @return 已删除的分析记录数（便于上层清理提取的 so 文件等）
     */
    @Transaction
    open suspend fun cascadeDeleteProject(projectId: Long): Int {
        val analysisDao = analysisDao()
        val analyses = analysisDao.getByProjectIdList(projectId)

        // 1. 删子表（按 analysis_id）
        for (analysis in analyses) {
            dartCallGraphDao().deleteByAnalysisId(analysis.id)
            ppEntryDao().deleteByAnalysisId(analysis.id)
            dartMethodDao().deleteByAnalysisId(analysis.id)
            dartClassDao().deleteByAnalysisId(analysis.id)
            libraryDao().deleteByAnalysisId(analysis.id)
            dartObjectDao().deleteByAnalysisId(analysis.id)
            enumMapDao().deleteByAnalysisId(analysis.id)
            asmBlockDao().deleteByAnalysisId(analysis.id)
        }

        // 2. 删 analyses
        analysisDao.deleteByProjectId(projectId)

        // 3. 删 address_mappings（按 project_id）
        addressMappingDao().deleteByProjectId(projectId)

        // 4. 删 projects
        projectDao().deleteById(projectId)

        return analyses.size
    }

    /**
     * 级联删除单条分析记录及其所有子数据。
     *
     * 与 [cascadeDeleteProject] 同样的原因：SQLite 默认不开启外键约束，
     * ForeignKey(onDelete = CASCADE) 不会自动触发，需在应用层显式级联。
     *
     * 删除顺序（从叶子到根）：
     * 1. pp_entries / dart_methods / dart_classes / libraries —— 按 analysis_id
     * 2. analyses —— 根记录
     *
     * 注意：address_mappings 按 project_id 关联（不属 analysis），不在此清理。
     * 若被删的分析是项目最后一条分析，项目本体保留（项目状态由调用方决定是否更新）。
     *
     * @param analysisId 要删除的分析记录 ID
     */
    @Transaction
    open suspend fun cascadeDeleteAnalysis(analysisId: Long) {
        dartCallGraphDao().deleteByAnalysisId(analysisId)
        ppEntryDao().deleteByAnalysisId(analysisId)
        dartMethodDao().deleteByAnalysisId(analysisId)
        dartClassDao().deleteByAnalysisId(analysisId)
        libraryDao().deleteByAnalysisId(analysisId)
        dartObjectDao().deleteByAnalysisId(analysisId)
        enumMapDao().deleteByAnalysisId(analysisId)
        asmBlockDao().deleteByAnalysisId(analysisId)
        analysisDao().deleteById(analysisId)
    }

    companion object {
        const val DATABASE_NAME = "fler_database"
    }
}
