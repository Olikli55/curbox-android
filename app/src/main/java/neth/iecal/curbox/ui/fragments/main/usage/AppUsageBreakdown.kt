package neth.iecal.curbox.ui.fragments.main.usage

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.color.MaterialColors
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentAppUsageBreakdownBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.CreateAppGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.CreateGrayscaleGroupFragment
import neth.iecal.curbox.utils.TimeTools

class AppUsageBreakdown(private val stat: AppUsageStat) : Fragment() {

    private lateinit var binding: FragmentAppUsageBreakdownBinding
    private val viewModel: SetupShortcutViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentAppUsageBreakdownBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLineChart(binding.lineChart)
        plotUsageData()

        try {
            val appInfo = requireContext().packageManager.getApplicationInfo(stat.packageName, 0)
            binding.appName.text = appInfo.loadLabel(requireContext().packageManager)
            binding.appIcon.setImageDrawable(appInfo.loadIcon(requireContext().packageManager))
        } catch (_: Exception) {}
        
        binding.screentime.text = TimeTools.formatTime(stat.totalTime, false)
        binding.sessions.text = stat.sessions.toString()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settings.collectLatest { settings ->
                if (settings == null) return@collectLatest
                binding.dynamicShortcutsContainer.removeAllViews()

                val matchedAppGroups = settings.blockedAppGroups.filter { it.selectedPackages.contains(stat.packageName) }
                matchedAppGroups.forEach { group ->
                    addShortcutCard(
                        title = group.name,
                        subtitle = getString(R.string.rule_type_app_blocker),
                        isActive = group.isActive,
                        iconRes = R.drawable.ic_app_blocker_aesthetic,
                        onToggle = { active -> viewModel.toggleAppGroup(group.id, active) },
                        onClick = {
                            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                                putExtra("fragment", CreateAppGroupFragment.FRAGMENT_ID)
                                putExtra("group_id", group.id)
                            })
                        }
                    )
                }

                val matchedGrayscale = settings.grayscaleGroups.filter { it.packages.contains(stat.packageName) }
                matchedGrayscale.forEach { group ->
                    addShortcutCard(
                        title = group.groupName,
                        subtitle = getString(R.string.rule_type_grayscale),
                        isActive = group.isActive,
                        iconRes = R.drawable.ic_grayscale_aesthetic,
                        onToggle = { active -> viewModel.toggleGrayscaleGroup(group.groupId, active) },
                        onClick = {
                            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                                putExtra("fragment", CreateGrayscaleGroupFragment.FRAGMENT_ID)
                                putExtra("group_id", group.groupId)
                            })
                        }
                    )
                }
            }
        }

        binding.btnCreateNewRule.setOnClickListener {
            val options = arrayOf(getString(R.string.rule_type_app_blocker), getString(R.string.rule_type_grayscale))
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.create_new_rule_title)
                .setItems(options) { _, which ->
                    val fragmentId = when (which) {
                        0 -> CreateAppGroupFragment.FRAGMENT_ID
                        1 -> CreateGrayscaleGroupFragment.FRAGMENT_ID
                        else -> return@setItems
                    }
                    startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                        putExtra("fragment", fragmentId)
                        putExtra("prefill_package", stat.packageName)
                    })
                }
                .show()
        }
    }

    private fun addShortcutCard(
        title: String,
        subtitle: String,
        isActive: Boolean,
        iconRes: Int,
        onToggle: ((Boolean) -> Unit)?,
        onClick: () -> Unit
    ) {
        val item = LayoutInflater.from(requireContext()).inflate(R.layout.item_usage_shortcut, binding.dynamicShortcutsContainer, false)
        item.findViewById<TextView>(R.id.tv_title).text = title
        item.findViewById<TextView>(R.id.tv_subtitle).text = subtitle
        
        try {
            item.findViewById<ImageView>(R.id.icon_type).setImageResource(iconRes)
        } catch (_: Exception) {}

        val switchView = item.findViewById<SwitchMaterial>(R.id.switch_active)
        if (onToggle != null) {
            switchView.visibility = View.VISIBLE
            switchView.isChecked = isActive
            switchView.setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        } else {
            switchView.visibility = View.GONE
        }

        item.setOnClickListener { onClick() }
        binding.dynamicShortcutsContainer.addView(item)
    }

    private fun setupLineChart(lineChart: LineChart) {
        lineChart.apply {
            description.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(false)
            setPinchZoom(false)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                labelRotationAngle = 45f
                valueFormatter = HourAxisFormatter()
            }
            axisLeft.apply {
                valueFormatter = MinutesAxisFormatter()
                axisMinimum = 0f
            }
            axisRight.isEnabled = false
            animateX(1000)
        }
    }

    private fun plotUsageData() {
        val hourlyUsage = stat.hourlyUsage
        val entries = hourlyUsage.mapIndexed { hour, durationMs ->
            Entry(hour.toFloat(), durationMs / (1000f * 60f))
        }
        val dataSet = LineDataSet(entries, "Usage Time (minutes)")
        setupChartUI(binding.lineChart, dataSet)
    }

    private fun setupChartUI(chart: LineChart, lineDataSet: LineDataSet) {
        val primaryColor = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorPrimary, ContextCompat.getColor(requireContext(), R.color.text_color))
        lineDataSet.apply {
            color = primaryColor
            valueTextColor = primaryColor
            lineWidth = 3f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.2f
        }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            labelCount = 5
            setDrawGridLines(false)
            textColor = primaryColor
        }
        chart.axisLeft.apply {
            isEnabled = true
            setDrawGridLines(false)
            textColor = primaryColor
            valueFormatter = MinutesAxisFormatter()
            axisMinimum = 0f
        }
        chart.apply {
            axisRight.isEnabled = false
            legend.isEnabled = false
            description.isEnabled = false
            animateY(800, Easing.EaseInCubic)
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            setPinchZoom(false)
            data = LineData(lineDataSet)
        }
        chart.invalidate()
    }

    private class HourAxisFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val hour = value.toInt()
            return String.format("%02d:00", hour)
        }
    }

    private class MinutesAxisFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val totalMinutes = value.toInt()
            if (totalMinutes == 0) return "0m"
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) {
                if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            } else {
                "${minutes}m"
            }
        }
    }

    private class MinutesValueFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            if (value == 0f) return ""
            return "${value.toInt()}m"
        }
    }
}
