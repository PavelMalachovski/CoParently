# CoPlanly — Privacy Policy

> **DRAFT. Not yet reviewed by a lawyer, and not yet published.**
>
> Every `{{PLACEHOLDER}}` must be filled in. More importantly: this app processes **a child's
> health data**, which is special-category data under GDPR Art. 9, and no template survives
> that unread. Have a lawyer review it before it goes anywhere near a store listing.
>
> Written from the actual data model — the Firestore collections, the Storage buckets and the
> third parties the code really talks to — rather than from a template. If the code changes,
> this changes with it. See `docs/legal/DATA-SAFETY.md` for the same facts in the shape the
> Play Console asks for.

**Last updated:** {{DATE}}
**Effective:** {{DATE}}

## Who we are

CoPlanly ("the app") is operated by {{LEGAL_ENTITY_NAME}}, {{REGISTERED_ADDRESS}}
({{COMPANY_ID}}). We are the **data controller** for the personal data described here.

Contact for any privacy question, including the rights listed below: {{PRIVACY_CONTACT_EMAIL}}.

{{DPO_PARAGRAPH_IF_APPOINTED}}

## What CoPlanly is for

CoPlanly is a shared calendar for parents raising a child in two homes. Two parents each hold
an account, link them to one another, and share a custody schedule, events, expenses, records
about their child, and a private message thread.

That shape has a consequence worth stating plainly, because it is unusual: **most of what you
enter is deliberately visible to your co-parent.** Sharing is the product, not a side effect.
Where something is *not* shared, we say so below.

## What we collect, and why

### Your account

- Email address and display name; a profile photo if you sign in with Google.
- An authentication identifier from Firebase Authentication.
- Which parent slot you occupy in your family, and who your linked co-parent is.

**Why:** to give you an account, to show your co-parent who they are linked with, and to
decide what each of you may read. **Legal basis:** performance of our contract with you
(Art. 6(1)(b)).

### What you enter about your family

- **Calendar events** — titles, times, locations, notes, event types, and optional photos.
- **The custody schedule** — the pattern you agree and any one-off day swaps.
- **Expenses and budgets** — amounts, currencies, categories, and optional receipt photos.
- **Records about your child** — name, date of birth, school and activity details, emergency
  contacts, and a medical profile: allergies, medications, conditions, blood group,
  vaccinations, doctors' notes and photographs you attach to them.
- **Records about a pet**, in the same shape.
- **Messages** between you and your co-parent.

**Why:** these are the contents of the service. **Legal basis:** performance of our contract
with you (Art. 6(1)(b)). For the medical profile, which is health data under Art. 9, we rely
on your **explicit consent** (Art. 9(2)(a)): the medical fields are optional, you choose
whether to fill them, and you can delete them at any time. The app works without them.

### Data about a child

A child does not hold an account and never signs in. What is recorded about them is entered by
a parent, and only their parents — and anyone a parent explicitly grants access to — can read
it. In the Czech Republic the digital-consent age is 15 (§ 7 of zákon č. 110/2019 Sb.); we do
not offer accounts to anyone under 18, and we rely on the parent's own authority over their
child's records rather than on the child's consent.

### People you invite

- **Your co-parent**: their email address, if you invite them by email.
- **A guest** (for example a grandparent) whom you grant time-limited access to one child's
  record.
- **A calendar friend** whom you grant time-limited read access to the family calendar.

Every such grant carries an expiry, is visible to both parents, and can be revoked at any time.

### Technical data

- **A push notification token**, so we can notify you about changes your co-parent makes.
- **Crash reports** (Firebase Crashlytics) and **usage analytics** (Firebase Analytics) in
  release builds. These carry no message content, no event titles and no records about your
  child. They record which screens are opened and which actions succeed or fail.
  {{CONSENT_SENTENCE_ONCE_CONSENT_SCREEN_SHIPS}}

### Google Calendar, if you connect it

If you connect a Google account, we request access to your calendars so events can be
imported and exported. We store the resulting access and refresh tokens **encrypted on your
device only** — they are never sent to our servers. Disconnecting in Settings deletes them.

## What happens on your device and goes nowhere

- **Receipt scanning.** When you photograph a receipt, the text is recognised **entirely on
  your device**. The photograph and the recognised text are not sent to any text-recognition
  or AI service.
- **Private events.** An event you mark private never leaves your device. It is not uploaded,
  not synced, and not visible to your co-parent.

## Who else sees your data

We do not sell personal data, and we do not use it for advertising.

| Recipient | What they process | Why |
| --- | --- | --- |
| **Your co-parent** | Everything you share — which is most of it | The purpose of the service |
| **Guests and calendar friends you invite** | Only the record or calendar you granted, until the grant expires | Because you granted it |
| Google (Firebase) | Account data, all synced content, files, push tokens, crash and usage data | Our hosting, database, file storage and messaging provider |
| Google (Calendar API) | Only your calendar, only if you connect it | The integration you enabled |
| {{MAIL_PROVIDER}} | The recipient's email address and the invitation text | To deliver invitation emails |

Google processes data both inside and outside the EU. Transfers outside the EEA rely on the
European Commission's Standard Contractual Clauses. {{FIRESTORE_REGION_SENTENCE}}

We disclose data to authorities only where the law requires it.

## How long we keep it

We keep what you enter for as long as your account exists. When you delete your account
(below), it is removed as described there. Guest and friend grants expire automatically on the
date set when they were issued, and a daily job removes lapsed ones. Old queued notifications
are deleted after 30 days.

## Deleting your account

**Settings → Account → Delete account.** This is irreversible and, once confirmed, it:

- deletes your profile, your events, your expenses and budgets, the records you entered about
  your child and pet, your custody schedule, your invitations, and the whole message thread
  with your co-parent;
- removes you from the audience of anything your co-parent created;
- unlinks the two of you, so their access ends immediately;
- deletes your authentication account;
- wipes the local copy on the device you did it from.

One consequence, stated plainly because it surprises people: **records your co-parent entered
remain in their account, and records you entered disappear from theirs.** Deleting your data
means deleting it everywhere, including from the calendar you shared.

If you no longer have the app installed, write to {{PRIVACY_CONTACT_EMAIL}} and we will delete
the account for you. {{WEB_DELETION_URL}}

## Your rights

Under the GDPR you may: access your data; correct it; delete it; restrict or object to its
processing; receive it in a portable form; and withdraw a consent you have given, without
affecting what was done before you withdrew it.

Most of these you can exercise directly in the app — everything you entered is visible and
editable, and deletion is one screen away. For anything else, write to
{{PRIVACY_CONTACT_EMAIL}}; we answer within one month.

You may also complain to a supervisory authority. In the Czech Republic that is the Office for
Personal Data Protection (Úřad pro ochranu osobních údajů), Pplk. Sochora 27, 170 00 Praha 7,
[uoou.gov.cz](https://uoou.gov.cz).

## Security

Data in transit is encrypted. Access to your family's data is enforced server-side, so another
account cannot read it by asking. The app's own database on your device is encrypted too, with a
key held in the Android Keystore that cannot be copied off the device — so the calendar, messages,
expenses and any medical details you enter are not readable by someone holding the phone.
Authentication tokens are stored in encrypted storage backed by the same Keystore, and if that
storage cannot be opened, they are held in memory only rather than written unprotected. Device
backup and device-to-device transfer of the app's data are switched off.

No system is perfect. If we discover a breach affecting your rights, we will notify the
supervisory authority within 72 hours and tell you where the law requires it.

## Changes

If we change this policy in a way that affects you, we will tell you in the app before the
change takes effect.
