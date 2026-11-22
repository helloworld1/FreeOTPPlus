package org.fedorahosted.freeotp.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.databinding.AboutBinding

@AndroidEntryPoint
class AboutFragment : Fragment() {
    private var _binding: AboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = AboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.aboutScrollView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                resources.getDimensionPixelSize(R.dimen.space_3) + insets.bottom
            )
            windowInsets
        }

        val pm = requireContext().packageManager
        val info = pm.getPackageInfo(requireContext().packageName, 0)
        binding.aboutVersion.text = getString(
            R.string.about_version,
            info.versionName,
            PackageInfoCompat.getLongVersionCode(info)
        )

        val movementMethod = object : LinkMovementMethod() {
            override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
                return try {
                    super.onTouchEvent(widget, buffer, event)
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
        }

        val github = getString(R.string.link_github)
        val website = getString(R.string.about_website, github)
        binding.aboutWebsite.movementMethod = movementMethod
        binding.aboutWebsite.text = HtmlCompat.fromHtml(website, HtmlCompat.FROM_HTML_MODE_COMPACT)

        val apache2 = getString(R.string.link_apache2)
        val license = getString(R.string.about_license, apache2)

        binding.aboutLicense.movementMethod = movementMethod
        binding.aboutLicense.text = HtmlCompat.fromHtml(license, HtmlCompat.FROM_HTML_MODE_COMPACT)

        binding.aboutTokenImage.movementMethod = movementMethod
        binding.aboutTokenImage.text = HtmlCompat.fromHtml(
            getString(R.string.about_token_image),
            HtmlCompat.FROM_HTML_MODE_COMPACT
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
