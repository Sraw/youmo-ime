/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import timber.log.Timber

class CandidatesPagingSource(val fcitx: FcitxConnection, val total: Int, val offset: Int) :
    PagingSource<Int, CandidateWord>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CandidateWord> {
        // use candidate index for key, null means load from beginning (with offset)
        val startIndex = params.key ?: offset
        val pageSize = params.loadSize
        Timber.d("getCandidates(offset=$startIndex, limit=$pageSize)")
        val candidates = fcitx.runOnReady {
            getCandidates(startIndex, pageSize)
        }
        val prevKey = if (startIndex >= pageSize) startIndex - pageSize else null
        return LoadResult.Page(candidates.toList(), prevKey, nextKey(startIndex, candidates.size, pageSize, total))
    }

    // always reload from beginning
    override fun getRefreshKey(state: PagingState<Int, CandidateWord>) = null

    companion object {
        /**
         * Where the page after [loaded] candidates asked for from [startIndex], [pageSize] of
         * them, starts; null when that page was the last: it reached [total], where that is
         * known (above 0), or else came back short.
         */
        fun nextKey(startIndex: Int, loaded: Int, pageSize: Int, total: Int): Int? = when {
            total > 0 -> if (startIndex + pageSize >= total) null else startIndex + pageSize
            loaded < pageSize -> null
            else -> startIndex + pageSize
        }
    }

}
