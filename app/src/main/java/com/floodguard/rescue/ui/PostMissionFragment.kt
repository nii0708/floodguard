package com.floodguard.rescue.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.floodguard.rescue.R
import com.floodguard.rescue.session.SessionExporter
import com.floodguard.rescue.state.MissionState
import android.graphics.PointF
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PostMissionFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_post_mission, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val state = viewModel.missionState.value
        if (state !is MissionState.Review) return
        val log = state.sessionData

        val endTimeText = view.findViewById<TextView>(R.id.review_end_time)
        val durationValue = view.findViewById<TextView>(R.id.review_duration_value)
        val distanceSubtext = view.findViewById<TextView>(R.id.review_distance_subtext)
        val landmarkValue = view.findViewById<TextView>(R.id.review_landmark_value)
        val revisitValue = view.findViewById<TextView>(R.id.review_revisit_value)
        val reviewMap = view.findViewById<MapOverlayView>(R.id.review_map)
        val landmarkListContainer = view.findViewById<LinearLayout>(R.id.landmark_list_container)
        val btnExport = view.findViewById<Button>(R.id.btn_export)
        val btnFinish = view.findViewById<Button>(R.id.btn_finish)

        // Header
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        endTimeText.text = getString(R.string.review_end_time, timeFormat.format(Date(log.endTime)))

        // Summary cards
        durationValue.text = log.durationMinutes.toString()
        distanceSubtext.text = log.distanceMeters.toInt().toString()
        landmarkValue.text = log.landmarks.size.toString()
        revisitValue.text = log.revisitCount.toString()

        // Map in review mode
        reviewMap.isReviewMode = true
        reviewMap.updateTrail(log.trail.map { PointF(it.x, it.z) })
        reviewMap.updateLandmarks(log.landmarks)

        val currentIds = log.landmarks.map { it.id }.toSet()
        val filteredPast = viewModel.pastLandmarks.value.filter { it.id !in currentIds }
        if (filteredPast.isNotEmpty()) {
            reviewMap.updatePastLandmarks(filteredPast)
        }

        reviewMap.onLandmarkTapListener = { landmark ->
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.landmark_detail_title))
                .setMessage(landmark.description)
                .setPositiveButton("OK", null)
                .show()
        }

        // Landmark list
        val inflater = LayoutInflater.from(requireContext())
        log.landmarks.forEachIndexed { index, lm ->
            val itemView = inflater.inflate(R.layout.item_landmark, landmarkListContainer, false)
            itemView.findViewById<TextView>(R.id.landmark_number).text = (index + 1).toString()
            itemView.findViewById<TextView>(R.id.landmark_title).text = getString(R.string.review_landmark_item, index + 1)
            val descView = itemView.findViewById<TextView>(R.id.landmark_desc)
            val chevron = itemView.findViewById<ImageView>(R.id.chevron)
            descView.text = lm.description

            itemView.setOnClickListener {
                if (descView.maxLines == 1) {
                    descView.maxLines = Integer.MAX_VALUE
                    descView.ellipsize = null
                    chevron.rotation = 180f
                } else {
                    descView.maxLines = 1
                    descView.ellipsize = TextUtils.TruncateAt.END
                    chevron.rotation = 0f
                }
            }
            landmarkListContainer.addView(itemView)
        }

        // Export
        btnExport.setOnClickListener {
            try {
                val file = SessionExporter.export(requireContext(), log)
                Toast.makeText(requireContext(), getString(R.string.export_success, file.absolutePath), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }

        // Finish
        btnFinish.setOnClickListener {
            viewModel.endReview()
        }
    }
}
