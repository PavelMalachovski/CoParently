package com.coparently.app.domain.model

import androidx.annotation.StringRes
import com.coparently.app.R

/**
 * A ready-made message for a situation co-parents run into often.
 *
 * The visible text is held as **string resource ids**, not as strings. These templates used to
 * carry Russian literals, so every user was offered Russian no matter which of the five
 * languages the app was running in; the text belongs in the `chat_strings.xml` files.
 *
 * This deliberately differs from `ChangeRequestStatus` and `CustodyModelType`, which keep an
 * English `displayName` on the enum and are mapped to resources by the screen that renders
 * them. That shape fits a closed enum: the `when` in the presentation layer is exhaustive, so
 * the compiler catches a constant nobody mapped. The templates are a *list of data* instead —
 * a lookup keyed on [id] would be a parallel table the compiler cannot check, and a template
 * added without its mapping would render blank. `@StringRes` is a compile-time annotation over
 * a plain `Int`, so the domain layer still holds no `Context` and no Android runtime type.
 *
 * @property id Unique identifier for the template
 * @property category Category of the template
 * @property titleRes Display title of the template
 * @property contentRes Message body. It carries hints in square brackets for the user to
 *   replace before sending.
 * @property placeholders Locale-independent names of the values a template asks the user to
 *   fill in. **They are not substrings of the rendered [contentRes]**: the bracketed hints in
 *   the body are translated per locale, because the body is seeded into the composer for the
 *   user to edit by hand and a Czech parent should be prompted in Czech. Nothing substitutes
 *   them today — `ChatScreen` seeds the composer with the body verbatim, and no code has ever
 *   read this list — so keeping the old Russian words here would have been neither a working
 *   mechanism nor readable. A substitution feature added later must resolve the visible token
 *   from resources; matching these names against the rendered text would only ever work in
 *   English.
 */
data class MessageTemplate(
    val id: String,
    val category: TemplateCategory,
    @StringRes val titleRes: Int,
    @StringRes val contentRes: Int,
    val placeholders: List<String> = emptyList()
)

/**
 * Categories for message templates.
 *
 * @property labelRes Group heading shown above the category's templates.
 */
enum class TemplateCategory(@StringRes val labelRes: Int) {
    PICKUP_DROP(R.string.chat_template_category_pickup_drop),
    ILLNESS(R.string.chat_template_category_illness),
    SCHOOL_EVENTS(R.string.chat_template_category_school_events),
    HOLIDAYS(R.string.chat_template_category_holidays),
    CONFLICT_RESOLUTION(R.string.chat_template_category_conflict_resolution)
}

/**
 * Default message templates for common situations.
 */
object DefaultMessageTemplates {
    fun getAll(): List<MessageTemplate> = listOf(
        MessageTemplate(
            id = "pickup_delay",
            category = TemplateCategory.PICKUP_DROP,
            titleRes = R.string.chat_template_pickup_delay_title,
            contentRes = R.string.chat_template_pickup_delay_content,
            placeholders = listOf("child", "minutes")
        ),
        MessageTemplate(
            id = "pickup_early",
            category = TemplateCategory.PICKUP_DROP,
            titleRes = R.string.chat_template_pickup_early_title,
            contentRes = R.string.chat_template_pickup_early_content,
            placeholders = listOf("time")
        ),
        MessageTemplate(
            id = "doctor_visit",
            category = TemplateCategory.ILLNESS,
            titleRes = R.string.chat_template_doctor_visit_title,
            contentRes = R.string.chat_template_doctor_visit_content,
            placeholders = listOf("child", "symptom", "diagnosis", "recommendation")
        ),
        MessageTemplate(
            id = "child_sick",
            category = TemplateCategory.ILLNESS,
            titleRes = R.string.chat_template_child_sick_title,
            contentRes = R.string.chat_template_child_sick_content,
            placeholders = listOf("child", "symptoms", "temperature")
        ),
        MessageTemplate(
            id = "school_event",
            category = TemplateCategory.SCHOOL_EVENTS,
            titleRes = R.string.chat_template_school_event_title,
            contentRes = R.string.chat_template_school_event_content,
            placeholders = listOf("event", "date", "time")
        ),
        MessageTemplate(
            id = "parent_teacher_meeting",
            category = TemplateCategory.SCHOOL_EVENTS,
            titleRes = R.string.chat_template_parent_teacher_meeting_title,
            contentRes = R.string.chat_template_parent_teacher_meeting_content,
            placeholders = listOf("date", "time", "topic")
        ),
        MessageTemplate(
            id = "holiday_plan",
            category = TemplateCategory.HOLIDAYS,
            titleRes = R.string.chat_template_holiday_plan_title,
            contentRes = R.string.chat_template_holiday_plan_content,
            placeholders = listOf("holiday", "proposal")
        ),
        MessageTemplate(
            id = "schedule_change",
            category = TemplateCategory.CONFLICT_RESOLUTION,
            titleRes = R.string.chat_template_schedule_change_title,
            contentRes = R.string.chat_template_schedule_change_content,
            placeholders = listOf("date", "alternative date")
        )
    )
}
