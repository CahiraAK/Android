/*
 * Copyright (c) 2024 DuckDuckGo
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

package com.duckduckgo.autofill.impl.importing

import com.duckduckgo.autofill.api.domain.app.LoginCredentials
import com.duckduckgo.autofill.impl.importing.PasswordImporter.ImportResult
import com.duckduckgo.autofill.impl.store.InternalAutofillStore
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject
import kotlinx.coroutines.withContext

interface PasswordImporter {
    suspend fun importPasswords(importList: List<LoginCredentials>): ImportResult

    data class ImportResult(val savedCredentialIds: List<Long>, val duplicatedPasswords: List<LoginCredentials>)
}

@ContributesBinding(AppScope::class)
class PasswordImporterImpl @Inject constructor(
    private val existingPasswordMatchDetector: ExistingPasswordMatchDetector,
    private val autofillStore: InternalAutofillStore,
    private val dispatchers: DispatcherProvider,
) : PasswordImporter {

    override suspend fun importPasswords(importList: List<LoginCredentials>): ImportResult {
        return withContext(dispatchers.io()) {
            val savedCredentialIds = mutableListOf<Long>()
            val duplicatedPasswords = mutableListOf<LoginCredentials>()

            importList.forEach {
                if (!existingPasswordMatchDetector.alreadyExists(it)) {
                    val insertedId = autofillStore.saveCredentials(it.domain!!, it)?.id

                    if (insertedId != null) {
                        savedCredentialIds.add(insertedId)
                    }
                } else {
                    duplicatedPasswords.add(it)
                }
            }

            ImportResult(savedCredentialIds, duplicatedPasswords)
        }
    }
}
