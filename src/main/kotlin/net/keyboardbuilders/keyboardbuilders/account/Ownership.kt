package net.keyboardbuilders.keyboardbuilders.account

import net.keyboardbuilders.keyboardbuilders.domain.Design

private val GITHUB_ACCOUNT = Regex("""^https://github\.com/([^/]+)/""")

/**
 * The GitHub accounts a design's sources live under, lowercased. Every design in the seed
 * has exactly one today (measured 2026-11-10: 348 designs, 190 accounts).
 */
fun Design.githubAccounts(): Set<String> =
    core.sources.mapNotNull { GITHUB_ACCOUNT.find(it.url)?.groupValues?.get(1)?.lowercase() }.toSet()

/**
 * Backlog #116's whole ownership rule: you own a design if its repo is under your GitHub
 * account. Logins are case-insensitive on GitHub, so the match is too. Organisation repos
 * do not match anyone yet; their designers email hello@.
 */
fun Design.isOwnedBy(login: String): Boolean = login.lowercase() in githubAccounts()
