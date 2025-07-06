package com.rommclient.android

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.FragmentManager
// import android.app.AlertDialog

class LoadingDialogFragment : DialogFragment() {

    private var alertDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        alertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Loading Games")
            .setMessage("Please wait while games are being fetched...")
            .setCancelable(false)
            .create()
        return alertDialog!!
    }

    override fun onDestroyView() {
        super.onDestroyView()
        alertDialog = null
    }

    companion object {
        private const val TAG = "LoadingDialog"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) {
                LoadingDialogFragment().show(manager, TAG)
            }
        }

        fun dismiss(manager: FragmentManager) {
            (manager.findFragmentByTag(TAG) as? DialogFragment)?.dismissAllowingStateLoss()
        }

        fun updateMessage(manager: FragmentManager, message: String) {
            val fragment = manager.findFragmentByTag(TAG) as? LoadingDialogFragment
            fragment?.alertDialog?.setMessage(message)
        }
    }
}