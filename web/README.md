# `web/` — the pages that have to exist outside the app

Two things Google Play requires of CoPlanly are URLs, not screens: a privacy policy and a route to
delete an account that works **without the app installed**. This directory holds the second one.
The first is `docs/legal/PRIVACY-POLICY.md`, which still needs a lawyer (REL-4) before it is worth
hosting.

## `delete-account/index.html`

A single self-contained file: no external CSS, fonts, scripts or images. That is deliberate — it
has to be hostable on anything, and a deletion page that fails because a CDN is down is worse than
not having one. The two languages are stacked rather than behind a toggle for the same reason:
nothing to fail, and a Play reviewer reading English finds it without interacting.

### Before hosting it

Fill the same placeholders the legal documents use, so one pass covers all three files:

| Placeholder | What goes in |
| --- | --- |
| `{{PRIVACY_CONTACT_EMAIL}}` | The address that will actually be read. It is the only route for somebody who has uninstalled the app. |
| `{{PRIVACY_POLICY_URL}}` | Where the privacy policy ends up. |
| `{{LEGAL_ENTITY_NAME}}` | The controller. |
| `{{REGISTERED_ADDRESS}}` | The controller's address — also required for EU trader status. |

```bash
grep -o '{{[A-Z_]*}}' web/delete-account/index.html | sort -u   # nothing left before publishing
```

### Two things for the lawyer, not for a developer

1. **"Within 30 days"** is the outer limit GDPR Art. 12(3) allows for responding to an erasure
   request. It is written that way rather than as a shorter promise on purpose — committing to
   less than the law requires creates an obligation nobody has staffed. Confirm it, and confirm
   that the identity check the page describes is the one you will actually perform.
2. **The retention answer is "nothing".** The page says no copy of a deleted account is kept for
   later restoration. That matches `deleteAccountDataImpl`, which hard-deletes rather than
   tombstoning. Whether the hosting provider's own backups make that sentence exactly true is a
   question about the Firebase DPA, and it is the kind of sentence a regulator reads closely.

### Keep three files in step

Every factual claim on the page mirrors `deleteAccountDataImpl` in `functions/index.js` and the
"Deleting your account" section of `docs/legal/PRIVACY-POLICY.md`. **If any of the three changes,
all three do.** The one most likely to drift is the list of collections: the callable deletes
`events`, `child_info`, `pets`, `expenses`, `budgets`, `change_requests`, `conversations`,
`messages`, `custody_models`, `family_settings`, `calendar_friends`, `friend_profiles`,
`invitations`, `notification_queue` and `users`, and adding a collection to the app without adding
it there leaves data behind that the page promises is gone.

### Hosting

Anything that serves a static file. Firebase Hosting is already in the project's orbit:

```jsonc
// firebase.json — "hosting" does not exist there yet; this is the shape it would take
"hosting": {
  "public": "web",
  "ignore": ["README.md"]
}
```

Then `firebase deploy --only hosting`, and the page is at `https://<site>/delete-account/`. Whatever
you choose, the URL goes in **two** places: the Play Console's data-deletion field, and
`{{WEB_DELETION_URL}}` in the privacy policy.

**Not wired into the app yet, on purpose.** Settings has no row linking to these URLs because they
do not resolve yet, and a row pointing at a dead link is exactly the affordance-promising-nothing
that design rule #8 in `CLAUDE.md` forbids. Add the rows in the same change that publishes the URLs.
