# Private content images

The content editor accepts an external image URL or a JPEG/PNG upload (10 MB maximum,
24 million pixels maximum). Uploads are decoded and re-encoded to remove metadata.
Click **Save** after uploading to attach the image to a work.

## Storage and database

The default backend stores files under `./data/content-images`, outside the frontend
public directory. Keep this directory on a persistent volume and back it up together
with the database. Application rebuilds do not remove it. Multiple application
instances must use shared storage or Supabase Storage.

Migration `V29__content_images.sql` stores ownership, original filename, content type,
size, storage backend and object key. `content_image_ref` connects images to documents,
including immutable recipient selections. The existing content JSON holds a stable
application image path; it never contains a service key or expiring storage URL.

## Access and retention

Owners can read their own images. Recipients receive a 30-minute, session-bound image
grant after successful passkey verification; copying the image URL alone grants no
access. Each request checks that the link still points to the document and that the
link is unexpired and the recipient is not revoked. Images use `Cache-Control: no-store`.
Reauthenticate through the protected link if the image grant expires.

Hourly cleanup deletes unreferenced uploads older than 24 hours. Saved documents and
recipient snapshots retain their images even when the original source is edited or
deleted. Removing the last reference makes an older image eligible for cleanup.

## Optional Supabase Storage

Create a **private** bucket named `content-images`, then configure the server `.env`:

```properties
plink.images.backend=supabase
plink.images.supabase-url=https://PROJECT.supabase.co
plink.images.supabase-key=SERVER_SERVICE_ROLE_KEY
plink.images.bucket=content-images
```

The service-role key stays on the backend. Uploads and reads pass through the
application, which enforces its own account/passkey permissions. Do not expose the
bucket publicly. See [Supabase private downloads](https://supabase.com/docs/guides/storage/serving/downloads)
and [server service keys](https://supabase.com/docs/guides/storage/security/access-control).
Existing local files remain local when switching backends; retain their volume.
Changing a bucket or project requires migrating existing objects first.
