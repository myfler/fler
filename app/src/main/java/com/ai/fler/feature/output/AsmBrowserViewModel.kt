package com.ai.fler.feature.output

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.data.dao.AsmBlockDao
import com.ai.fler.data.dao.DartMethodDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ASM 浏览器 ViewModel。
 *
 * 优先加载 dart_methods.src_code（反汇编伪代码）；空壳方法（src_code 仅占位/空）
 * 回退到 asm_blocks 完整反汇编（标准 DumpCode 格式，含语义注释）。
 */
@HiltViewModel
class AsmBrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dartMethodDao: DartMethodDao,
    private val asmBlockDao: AsmBlockDao
) : ViewModel() {

    private val analysisId: Long = savedStateHandle["analysisId"] ?: 0L
    private val methodId: Long = savedStateHandle["methodId"] ?: 0L

    private val _uiState = MutableStateFlow(AsmBrowserUiState())
    val uiState: StateFlow<AsmBrowserUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        loadAsmContent()
    }

    private fun loadAsmContent() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val result = withContext(Dispatchers.IO) {
                    dartMethodDao.getById(methodId)?.let { method ->
                        val src = method.srcCode ?: ""
                        // 空壳/占位（src_code 为空或只是方法名回声）时回退 asm_blocks
                        val body = if (src.isBlank()) {
                            asmBlockDao.getByMethodId(analysisId, methodId)?.body
                        } else null
                        val content = body ?: src
                        val lines = content.split("\n").filter { it.isNotEmpty() }
                        Triple(
                            method.methodName,
                            lines,
                            lines.size
                        )
                    }
                }

                if (result != null) {
                    val (name, lines, lineCount) = result
                    _uiState.value = AsmBrowserUiState(
                        analysisId = analysisId,
                        methodId = methodId,
                        fileName = name,
                        content = lines.joinToString("\n"),
                        lines = lines,
                        lineCount = lineCount,
                        isLoading = false
                    )
                } else {
                    _uiState.value = AsmBrowserUiState(
                        analysisId = analysisId,
                        methodId = methodId,
                        isLoading = false,
                        errorMessage = "未找到该方法的反汇编内容"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = AsmBrowserUiState(
                    analysisId = analysisId,
                    methodId = methodId,
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        loadAsmContent()
    }
}

data class AsmBrowserUiState(
    val analysisId: Long = 0L,
    val methodId: Long = 0L,
    val fileName: String = "",
    val content: String = "",
    val lines: List<String> = emptyList(),
    val lineCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
