package com.codria.screenshotshrinker.util

import com.codria.screenshotshrinker.R
import com.google.android.material.button.MaterialButton

class PresetSlotManager(
    private val saveButton: MaterialButton,
    private val slotButtons: List<MaterialButton>,
    private val topToast: TopToast,
    private val isSlotFilled: (slot: Int) -> Boolean,
    private val onSaveToSlot: (slot: Int) -> Unit,
    private val onLoadFromSlot: (slot: Int) -> Unit,
    private val onClearSlot: (slot: Int) -> Unit,
) {
    private var isSaveMode = false

    fun attachListeners() {
        saveButton.setOnClickListener { toggleSaveMode() }
        slotButtons.forEachIndexed { idx, btn ->
            val slot = idx + 1
            LongClickHelper.attach(
                btn,
                onClick = { handleSlotClick(slot) },
                onLongClick = { onClearSlot(slot) },
            )
        }
    }

    fun refreshIcons() {
        slotButtons.forEachIndexed { idx, btn ->
            val slot = idx + 1
            btn.setIconResource(
                if (isSlotFilled(slot)) R.drawable.ic_slot_filled else R.drawable.ic_slot_empty,
            )
        }
    }

    private fun toggleSaveMode() {
        isSaveMode = !isSaveMode
        if (isSaveMode) {
            saveButton.setText(R.string.action_save_preset_active)
            topToast.show(R.string.snackbar_pick_save_slot)
        } else {
            saveButton.setText(R.string.action_save_preset)
        }
    }

    private fun handleSlotClick(slot: Int) {
        if (isSaveMode) {
            onSaveToSlot(slot)
            isSaveMode = false
            saveButton.setText(R.string.action_save_preset)
        } else {
            onLoadFromSlot(slot)
        }
    }
}
