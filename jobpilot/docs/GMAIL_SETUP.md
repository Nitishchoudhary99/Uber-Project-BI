# Gmail auto-import: setup

The page lives at **`/gmail`** inside JobPilot. Credentials are never stored in the repo: they come from environment variables.

## 1. Create Google credentials (once)
1. [Google Cloud Console](https://console.cloud.google.com) → create/select a project.
2. **APIs & Services → Library → Gmail API → Enable.**
3. **OAuth consent screen**: User type *External*, app name *JobPilot*, add your support email and a privacy-policy URL.
   Add the scope `https://www.googleapis.com/auth/gmail.readonly`. While status is *Testing*, add your own Google accounts under **Test users** (up to 100).
4. **Credentials → Create credentials → OAuth client ID → Web application.**
   Add **Authorised redirect URI** exactly as you will set it below (e.g. `http://localhost:8000/gmail/callback`, and your https URL in production).
5. Copy the Client ID and Client secret.

## 2. Configure the server
```bash
export GOOGLE_CLIENT_ID="....apps.googleusercontent.com"
export GOOGLE_CLIENT_SECRET="...."
export GOOGLE_REDIRECT_URI="http://localhost:8000/gmail/callback"   # must match step 1.4 exactly
export GMAIL_TOKEN_KEY="$(python -c 'from cryptography.fernet import Fernet;print(Fernet.generate_key().decode())')"  # keep this secret, and keep it stable
export GMAIL_SYNC_MINUTES=60        # automatic import interval; 0 = off
python -m uvicorn app.main:app --port 8000
```
Without `GMAIL_TOKEN_KEY`, a dev key file is created next to the database. Do not rely on that in production: lose the key and every user must reconnect.

## 3. Use it
Open `/gmail` → allow "read job-alert emails" → *Connect Gmail* → approve at Google → *Import now*.

## Before real users
- `gmail.readonly` is a **restricted scope**. Google requires app verification, and a third-party security assessment (CASA) for public apps that store restricted-scope data. Until then the app is limited to test users and shows an "unverified app" warning.
  Plan this early; it can take weeks and has a cost.
- You need a published privacy policy that states the Gmail data use (Google's Limited Use policy: use only for the user-facing feature, no ads, no selling, no human reading except with consent/security/legal need).
- Run behind HTTPS, and keep `GMAIL_TOKEN_KEY` in a secret manager.

## What the integration does and does not do
- Lists only messages matching `from:(job-portal domains…) newer_than:Nd`, at most 100 per run; fetches each once (remembers message IDs).
- Stores the extracted job fields only, never email text. Imported jobs are private to the user.
- Disconnecting (or withdrawing the "alert emails" permission, or deleting the account) revokes the token at Google and deletes our copy.
- Google cannot restrict OAuth to specific senders, so the sender limit is enforced by our query, not by Google.
