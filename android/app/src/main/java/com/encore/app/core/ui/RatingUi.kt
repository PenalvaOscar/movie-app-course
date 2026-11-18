package com.encore.app.core.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.encore.app.R
import com.encore.app.core.data.rating.RatingValue
import com.encore.app.ui.theme.LocalRatingColors

/** Display order everywhere (buttons, filters): matches the Swipe cluster left to right. */
val RatingOrder = listOf(RatingValue.DISLIKE, RatingValue.UNSEEN, RatingValue.LIKE)

@get:DrawableRes
val RatingValue.icon: Int
    get() = when (this) {
        RatingValue.LIKE -> R.drawable.ic_like
        RatingValue.DISLIKE -> R.drawable.ic_dislike
        RatingValue.UNSEEN -> R.drawable.ic_unseen
    }

/** The verb, for buttons and accessibility actions ("Like"). */
@get:StringRes
val RatingValue.actionLabel: Int
    get() = when (this) {
        RatingValue.LIKE -> R.string.rating_like
        RatingValue.DISLIKE -> R.string.rating_dislike
        RatingValue.UNSEEN -> R.string.rating_unseen
    }

/** The state, for lists and messages ("Liked"). */
@get:StringRes
val RatingValue.stateLabel: Int
    get() = when (this) {
        RatingValue.LIKE -> R.string.rating_state_like
        RatingValue.DISLIKE -> R.string.rating_state_dislike
        RatingValue.UNSEEN -> R.string.rating_state_unseen
    }

@Composable
fun RatingValue.contentColor(): Color = LocalRatingColors.current.let {
    when (this) {
        RatingValue.LIKE -> it.likeContent
        RatingValue.DISLIKE -> it.dislikeContent
        RatingValue.UNSEEN -> it.unseenContent
    }
}

@Composable
fun RatingValue.borderColor(): Color = LocalRatingColors.current.let {
    when (this) {
        RatingValue.LIKE -> it.likeBorder
        RatingValue.DISLIKE -> it.dislikeBorder
        RatingValue.UNSEEN -> it.unseenBorder
    }
}

/** Icon plus state label in the value's color: never color alone. */
@Composable
fun RatingTag(value: RatingValue, modifier: Modifier = Modifier) {
    val color = value.contentColor()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(value.icon), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(stringResource(value.stateLabel), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * Like / Not seen / Dislike as one single-choice row (radio semantics for TalkBack). Choosing the
 * selected value again does nothing: a rating is only ever removed by Undo (AGENTS.md).
 */
@Composable
fun RatingChoiceRow(selected: RatingValue?, onSelect: (RatingValue) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        RatingOrder.forEachIndexed { index, value ->
            val color = value.contentColor()
            SegmentedButton(
                selected = value == selected,
                onClick = { if (value != selected) onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, RatingOrder.size),
                icon = { Icon(painterResource(value.icon), contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) },
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(stringResource(value.actionLabel), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
