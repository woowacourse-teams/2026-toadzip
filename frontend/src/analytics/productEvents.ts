/** Product events are explicit. Autocapture and arbitrary event/property names are not accepted. */
export const PRODUCT_EVENTS = [
  'page_view', 'view_complex', 'view_announcement',
  'welcome_shown', 'welcome_search_started', 'welcome_completed', 'starter_place_selected',
  'search_executed', 'search_results_loaded', 'search_failed', 'select_search_result',
  'search_more_requested', 'search_more_succeeded', 'search_more_failed', 'search_closed', 'region_selected',
  'list_opened', 'list_closed', 'list_scrolled', 'list_more_requested', 'list_more_succeeded', 'list_more_failed',
  'results_panel_snap_changed', 'map_marker_previewed', 'map_marker_selected', 'map_panned',
  'map_zoom_button_clicked', 'map_zoomed', 'region_boundary_action',
  'filter_opened', 'filter_closed', 'filter_value_changed', 'filter_reset_clicked', 'apply_filter',
  'exploration_retry_clicked', 'detail_open_requested', 'detail_scrolled', 'housing_type_selected',
  'detail_back_used', 'detail_closed', 'announcement_source_clicked',
  'document_preview_requested', 'document_preview_succeeded', 'document_preview_failed',
  'document_attachment_selected', 'document_viewer_action', 'document_download_requested',
  'document_download_handed_off', 'document_download_failed',
  'notification_cta_viewed', 'notification_cta_clicked', 'notification_form_viewed',
  'notification_form_submitted', 'notification_form_dismissed',
  'notification_preregistration_completed', 'notification_preregistration_failed',
  'notification_cancel_requested', 'notification_cancel_completed', 'notification_cancel_failed',
  'login_modal_opened', 'login_modal_closed', 'login_provider_clicked', 'account_menu_opened',
  'auth_state_changed', 'logout_requested', 'logout_succeeded', 'logout_failed',
  'feedback_started', 'feedback_submitted', 'feedback_succeeded', 'feedback_failed',
  'guest_cancellation_requested', 'guest_cancellation_request_accepted', 'guest_cancellation_request_failed',
  'guest_cancellation_verification_submitted', 'guest_bulk_cancellation_completed',
  'guest_cancellation_verification_failed',
  'street_view_open_requested', 'street_view_blocked', 'street_view_viewed', 'street_view_failed',
  'street_view_retry_clicked', 'street_view_closed', 'street_view_marker_status',
] as const

export type ProductEventName = typeof PRODUCT_EVENTS[number]
export type ProductProperties = Record<string, unknown>
export type SafeProperties = Record<string, string | number | boolean | string[]>

const PUBLIC_IDS = new Set(['complex_id', 'announcement_id', 'housing_type_id', 'attachment_id', 'target_id', 'result_id', 'region_code'])
const CORRELATION_IDS = new Set([
  'event_id', 'detail_visit_id', 'list_view_id', 'search_id', 'filter_edit_id', 'document_open_id',
  'action_id', 'server_event_id', 'street_view_open_id', 'attempt_id', 'exposure_id',
  'request_id', 'notification_action_id', 'login_modal_id', 'feedback_id', 'submission_id', 'cancellation_id',
  'document_dialog_id', 'document_download_id',
])
const COUNTS = new Set([
  'result_count', 'complex_count', 'announcement_count', 'region_count', 'loaded_count', 'added_count',
  'rank', 'position', 'filter_count', 'zoom_before', 'zoom_after', 'zoom_target', 'page_number',
  'attachment_count', 'selected_index', 'result_index', 'count', 'elapsed_ms', 'duration_ms',
  'group_count', 'failed_group_count', 'page',
  'preview_attempt', 'appended_count',
])
const FLAGS = new Set(['had_changes', 'has_unapplied_changes', 'unapplied_changes', 'was_viewed', 'aligned', 'has_more', 'is_empty'])
const TOKENS = new Set([
  'surface', 'entry_point', 'method', 'starter_place_id', 'result_type', 'query_length_bucket',
  'list_type', 'from', 'to', 'reason', 'failure_reason', 'error_code', 'source', 'stage', 'direction',
  'region_level', 'action', 'topic', 'layout', 'field', 'preset_id', 'reset_scope', 'state_target',
  'filter_target', 'apply_mode', 'operation', 'target_type', 'outcome', 'provider', 'previous_auth_state',
  'next_auth_state', 'view_state', 'marker_status', 'alignment_status', 'document_type', 'format',
  'selection_method', 'close_reason', 'snap', 'previous_snap', 'next_snap', 'status',
  'completion_source',
  'marker_type', 'from_snap', 'to_snap',
])
const TOKEN_LISTS = new Set(['changed_fields', 'methods_used', 'filter_types'])
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i
const TOKEN = /^[a-zA-Z0-9_+-]{1,80}$/

export function isProductEvent(name: string): name is ProductEventName {
  return PRODUCT_EVENTS.some((event) => event === name)
}

export function sanitizeProductProperties(input: ProductProperties): SafeProperties {
  const output: SafeProperties = {}
  for (const [key, value] of Object.entries(input)) {
    if (PUBLIC_IDS.has(key) && /^\d{1,19}$/.test(String(value)) && Number(value) > 0) output[key] = String(value)
    if (CORRELATION_IDS.has(key) && typeof value === 'string' && UUID.test(value)) output[key] = value
    if (COUNTS.has(key) && typeof value === 'number' && Number.isFinite(value) && value >= 0) output[key] = value
    if (FLAGS.has(key) && typeof value === 'boolean') output[key] = value
    if (TOKENS.has(key) && typeof value === 'string' && TOKEN.test(value)) output[key] = value
    if (TOKEN_LISTS.has(key)) {
      const values = typeof value === 'string' ? value.split(',') : value
      if (Array.isArray(values) && values.length <= 30 && values.every((part) => typeof part === 'string' && TOKEN.test(part))) {
        output[key] = [...new Set(values)]
      }
    }
    if (key === 'occurred_at' && typeof value === 'string' && /^\d{4}-\d\d-\d\dT[\d:.]+(?:Z|[+-]\d\d:\d\d)$/.test(value) && Number.isFinite(Date.parse(value))) {
      output[key] = value
    }
  }
  return output
}
