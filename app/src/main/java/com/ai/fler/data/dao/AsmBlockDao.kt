package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.AsmBlock

/**
 * ASM 反汇编块 DAO（Blutter asm 完整产物导入表）。
 */
@Dao
interface AsmBlockDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(blocks: List<AsmBlock>)

    @Query("DELETE FROM asm_blocks WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT * FROM asm_blocks WHERE analysis_id = :analysisId ORDER BY vaddr")
    suspend fun getByAnalysisId(analysisId: Long): List<AsmBlock>

    /** 按 vaddr（function_offset 同坐标）精确取反汇编块。 */
    @Query("SELECT * FROM asm_blocks WHERE analysis_id = :analysisId AND vaddr = :vaddr LIMIT 1")
    suspend fun getByVaddr(analysisId: Long, vaddr: Long): AsmBlock?

    /** 按方法 id 精确取反汇编块。 */
    @Query("SELECT * FROM asm_blocks WHERE analysis_id = :analysisId AND method_id = :methodId LIMIT 1")
    suspend fun getByMethodId(analysisId: Long, methodId: Long): AsmBlock?

    @Query("SELECT COUNT(*) FROM asm_blocks WHERE analysis_id = :analysisId")
    suspend fun countByAnalysisId(analysisId: Long): Int

    /** 空 src_code 的方法中，有多少能在 asm_blocks 找到完整反汇编（补全率统计）。 */
    @Query(
        "SELECT COUNT(DISTINCT ab.method_id) FROM asm_blocks ab " +
            "INNER JOIN dart_methods dm ON dm.id = ab.method_id " +
            "WHERE ab.analysis_id = :analysisId AND (dm.function_size IS NULL OR dm.function_size = 0)"
    )
    suspend fun countFillingEmptySrc(analysisId: Long): Int
}
