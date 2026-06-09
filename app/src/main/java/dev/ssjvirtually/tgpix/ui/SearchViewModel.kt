package dev.ssjvirtually.tgpix.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.ssjvirtually.tgpix.storage.LocalPhoto
import dev.ssjvirtually.tgpix.storage.UploadDatabase
import dev.ssjvirtually.tgpix.telegram.TdlibManager
import dev.ssjvirtually.tgpix.ui.screens.SearchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    @OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    fun searchPhotos(
        searchIndexFlow: StateFlow<List<SearchItem>>
    ): Flow<List<LocalPhoto>> {
        return combine(_searchQuery.debounce(200), searchIndexFlow, TdlibManager.dbVersion) { query, searchIndex, _ ->
            val trimmedQuery = query.trim().lowercase()
            if (trimmedQuery.isEmpty()) {
                emptyList()
            } else {
                // Filter local photos in memory
                val queryWords = trimmedQuery.split("\\s+".toRegex())
                val localFiltered = searchIndex.filter { item ->
                    queryWords.all { word -> item.keywords.contains(word) }
                }.map { item -> item.photo }

                // Search cloud photos using FTS
                val ftsQuery = trimmedQuery.split("\\s+".toRegex()).joinToString(" AND ") { "$it*" }
                val db = UploadDatabase.getDatabase(getApplication())
                val cloudResults = try {
                    db.cloudDao().searchCloudPhotos(ftsQuery)
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }

                val mappedCloud = cloudResults.map { cloud ->
                    val parsedDate = dev.ssjvirtually.tgpix.ui.utils.parseDateFromFilename(cloud.fileName)
                    val displayDate = if (cloud.dateTaken > 0L) cloud.dateTaken else (parsedDate ?: cloud.uploadedAt)
                    LocalPhoto(
                        id = -cloud.messageId,
                        uri = "cloud://${cloud.messageId}/${cloud.telegramFileId}/${cloud.fileName}",
                        name = cloud.fileName,
                        size = cloud.fileSize,
                        dateTaken = displayDate,
                        tags = cloud.tags
                    )
                }

                // Merge both lists, deduplicate by URI, and sort by dateTaken descending
                (localFiltered + mappedCloud)
                    .distinctBy { it.uri }
                    .sortedByDescending { it.dateTaken }
            }
        }.flowOn(Dispatchers.Default)
    }
}
