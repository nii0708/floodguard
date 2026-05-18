package com.floodguard.rescue.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.floodguard.rescue.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class TutorialBottomSheetFragment : BottomSheetDialogFragment() {
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_tutorial_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        view.findViewById<View>(R.id.btn_close).setOnClickListener {
            dismiss()
        }
    }
}
