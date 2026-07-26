package com.cocakova.charon.autocomplete

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wall that keeps visible secrets out of the autofill history: a genuine
 * command line whose token *names* declare a secret must never be learned —
 * a suggestion chip is a lock-screen-adjacent surface.
 */
class SecretGateTest {

    private fun secret(line: String) = assertTrue(line, SecretGate.carriesSecret(line))
    private fun clean(line: String) = assertFalse(line, SecretGate.carriesSecret(line))

    @Test
    fun assignmentsNamedForSecretsAreCaught() {
        secret("PGPASSWORD=hunter2 psql -h db")
        secret("export GITHUB_TOKEN=ghp_abc123")
        secret("AWS_SECRET_ACCESS_KEY=abc aws s3 ls")
        secret("API_KEY=xyz ./deploy.sh")
        secret("MYSQL_PWD=x mysql -u root db && echo AUTH_HEADER=done")
    }

    @Test
    fun longFlagsNamedForSecretsAreCaught() {
        secret("vault login --token=s.abc123")
        secret("mysql --password=hunter2 -u root db")
        secret("curl --oauth2-bearer abc https://api.example.com")
        secret("mc alias set s3 https://s3 --secret-key abc")
        secret("gh auth login --with-token")
    }

    @Test
    fun authorizationHeadersAreCaught() {
        secret("curl -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9' https://api.example.com")
        secret("curl -H \"authorization: Basic dXNlcjpwYXNz\" https://api.example.com")
        secret("http POST :8642/v1/chat Bearer abcdef123456 something")
    }

    /** Ordinary commands keep autofilling — including the near-misses. */
    @Test
    fun ordinaryCommandsPass() {
        clean("git log --author=jonny --oneline")
        clean("ssh-keygen -t ed25519 -f ~/.ssh/id_charon")
        clean("git push --force-with-lease")
        clean("systemctl restart nginx")
        clean("git commit -m 'fix auth flow copy'")
        clean("sudo apt install pass")
        clean("passwd")
        clean("ls -la ~/tokens")
        clean("docker compose up -d")
    }
}
