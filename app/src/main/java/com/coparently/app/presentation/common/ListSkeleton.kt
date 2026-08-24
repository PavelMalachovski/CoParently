package com.coparently.app.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.coparently.app.R
import com.coparently.app.presentation.components.SkeletonBox

/**
 * The placeholder a list shows while it does not yet know what is in it.
 *
 * One component rather than one per screen, because the point of [Loadable] is that every list
 * answers "not known yet" the same way. The shape is deliberately generic — rows of the right
 * height, nothing pretending to be a particular kind of content — since a skeleton that mimics
 * a specific row too closely reads as real data for the frame before it is replaced.
 *
 * Announced to a screen reader as a single "loading" node: the rows themselves are decorative and
 * announcing eight of them would be worse than announcing none.
 *
 * @param rows How many placeholder rows to draw.
 * @param rowHeight Height of each row.
 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = DEFAULT_ROWS,
    rowHeight: Dp = DEFAULT_ROW_HEIGHT
) {
    val loading = stringResource(R.string.common_loading)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(rows) {
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

private const val DEFAULT_ROWS = 6
private val DEFAULT_ROW_HEIGHT = 72.dp
