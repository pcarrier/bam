/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2023-present Benoit 'BoD' Lubek (BoD@JRAF.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.pcarrier.bam.util

import java.text.Normalizer

fun String.containsIgnoreAccents(s: String) =
    accentsStripped().contains(s.accentsStripped(), ignoreCase = true)

private val STRIP_ACCENT_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

private fun String.accentsStripped(): String {
    var string = Normalizer.normalize(this, Normalizer.Form.NFD)
    string = STRIP_ACCENT_REGEX.replace(string, "")
    return string
}
