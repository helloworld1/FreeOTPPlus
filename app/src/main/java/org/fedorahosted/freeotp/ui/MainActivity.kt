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

/*
 * Portions Copyright 2009 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fedorahosted.freeotp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

import android.app.Activity
import android.content.Intent
import android.database.DataSetObserver
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager.LayoutParams
import android.widget.GridView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.snackbar.Snackbar
import dagger.android.AndroidInjection

import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.util.Settings
import org.fedorahosted.freeotp.util.UiLifecycleScope
import org.fedorahosted.freeotp.token.TokenAdapter
import org.fedorahosted.freeotp.token.TokenPersistence
import org.fedorahosted.freeotp.util.ImportExportUtil
import javax.inject.Inject

private const val CAMERA_PERMISSION_REQUEST = 10
private const val READ_JSON_REQUEST_CODE = 42
private const val WRITE_JSON_REQUEST_CODE = 43
private const val READ_KEY_URI_REQUEST_CODE = 44
private const val WRITE_KEY_URI_REQUEST_CODE = 45

class MainActivity : AppCompatActivity() {

    @Inject lateinit var importFromUtil: ImportExportUtil
    @Inject lateinit var settings: Settings
    @Inject lateinit var uiLifecycleScope: UiLifecycleScope

    private lateinit var mTokenAdapter: TokenAdapter
    private lateinit var mDataSetObserver: DataSetObserver
    private lateinit var tokenPersistence: TokenPersistence
    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidInjection.inject(this)
        lifecycle.addObserver(uiLifecycleScope)

        onNewIntent(intent)
        setContentView(R.layout.main)

        rootView = findViewById(R.id.root)
        mTokenAdapter = TokenAdapter(this)
        (findViewById<View>(R.id.grid) as GridView).adapter = mTokenAdapter

        // Don't permit screenshots since these might contain OTP codes.
        window.setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE)

        mDataSetObserver = object : DataSetObserver() {
            override fun onChanged() {
                super.onChanged()
                if (mTokenAdapter.count == 0)
                    findViewById<View>(android.R.id.empty).visibility = View.VISIBLE
                else
                    findViewById<View>(android.R.id.empty).visibility = View.GONE
            }
        }
        mTokenAdapter.registerDataSetObserver(mDataSetObserver)
    }

    override fun onResume() {
        super.onResume()
        mTokenAdapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        mTokenAdapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        mTokenAdapter.unregisterDataSetObserver(mDataSetObserver)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menu.findItem(R.id.use_dark_theme).isChecked = settings.darkMode
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_scan -> {
                scanQRCode()
                return true
            }

            R.id.action_add -> {
                startActivity(Intent(this, AddActivity::class.java))
                return true
            }

            R.id.action_import_json -> {
                performFileSearch()
                return true
            }

            R.id.action_export_json -> {
                createFile("application/json", "freeotp-backup.json")
                return true
            }

            R.id.use_dark_theme -> {
                settings.darkMode = !settings.darkMode
                if (settings.darkMode) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                recreate()
                return true
            }

            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                return true
            }
        }

        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data
        if (uri != null)
            tokenPersistence.addFromUriString(uri.toString())
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int,
                                         resultData: Intent?) {
        if (requestCode == WRITE_JSON_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            uiLifecycleScope.launch {
                val uri = resultData?.data
                if (uri != null) {
                    importFromUtil.exportJson(uri)
                    Snackbar.make(rootView, R.string.export_succeeded_text, Snackbar.LENGTH_SHORT)
                            .show()
                }
            }
        }

        if (requestCode == READ_JSON_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            uiLifecycleScope.launch {
                val uri = resultData?.data
                if (uri != null) {
                    importFromUtil.importJson(uri)
                    mTokenAdapter.notifyDataSetChanged()
                    Snackbar.make(rootView, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                            .show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startActivity(Intent(this, ScanActivity::class.java))
                    overridePendingTransition(R.anim.fadein, R.anim.fadeout)
                } else {
                    Toast.makeText(this, R.string.camera_permission_denied_text, Toast.LENGTH_LONG).show()
                }
                return
            }
        }
    }

    /**
     * Fires an intent to spin up the "file chooser" UI and select an image.
     */
    private fun performFileSearch() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"

        startActivityForResult(intent, READ_JSON_REQUEST_CODE)
    }

    private fun createFile(mimeType: String, fileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)

        // Filter to only show results that can be "opened", such as
        // a file (as opposed to a list of contacts or timezones).
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        // Create a file with the requested MIME type.
        intent.type = mimeType
        intent.putExtra(Intent.EXTRA_TITLE, fileName)
        startActivityForResult(intent, WRITE_JSON_REQUEST_CODE)
    }

    private fun scanQRCode() {
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST)
        } else {
            startActivity(Intent(this, ScanActivity::class.java))
            overridePendingTransition(R.anim.fadein, R.anim.fadeout)
        }
    }
}
