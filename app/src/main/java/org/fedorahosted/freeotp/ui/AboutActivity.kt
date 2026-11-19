/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2013  Nathaniel McCallum, Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fedorahosted.freeotp.ui

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dagger.hilt.android.AndroidEntryPoint
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.databinding.AboutBinding
import org.fedorahosted.freeotp.util.AppSessionManager
import javax.inject.Inject

@AndroidEntryPoint
class AboutActivity : AppCompatActivity() {
    @Inject lateinit var appSessionManager: AppSessionManager
    private lateinit var binding: AboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!appSessionManager.ensureAuthenticatedOrFinish(this)) {
            return
        }
        enableEdgeToEdge()
        binding = AboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                insets.bottom
            )
            windowInsets
        }
    }

    public override fun onStart() {
        super.onStart()
        if (isFinishing) {
            return
        }

        setSupportActionBar(findViewById(R.id.toolbar))
        val res = resources
        val aboutVersion: TextView = findViewById(R.id.about_version)

        val pm = packageManager
        val info = pm.getPackageInfo(packageName, 0)
        val version = res.getString(R.string.about_version,
                info.versionName, PackageInfoCompat.getLongVersionCode(info))
        aboutVersion.text = version

        val apache2 = res.getString(R.string.link_apache2)
        val license = res.getString(R.string.about_license, apache2)

        binding.aboutLicense.movementMethod = LinkMovementMethod.getInstance()
        binding.aboutLicense.text = HtmlCompat.fromHtml(license, HtmlCompat.FROM_HTML_MODE_COMPACT)

        binding.aboutTokenImage.movementMethod = LinkMovementMethod.getInstance()
        binding.aboutTokenImage.text = HtmlCompat.fromHtml(getString(R.string.about_token_image),
                HtmlCompat.FROM_HTML_MODE_COMPACT)
    }
}
