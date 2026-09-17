import type { ExhibitionBody } from '../types';

/**
 * A document as its recipient reads it.
 *
 * <p>The same shape the studio composes, rendered plainly: this is the destination of a
 * protected link, so it opens where the reader already is rather than sending them on.
 */
export default function ContentView({ title, body }: { title: string; body: ExhibitionBody }) {
  const artworks = body.artworks ?? [];
  return (
    <article className="content-view">
      <h2>{title}</h2>
      {body.intro && <p className="content-intro">{body.intro}</p>}
      <div className={body.columns === '1' ? 'content-grid one' : 'content-grid'}>
        {artworks.map((artwork, index) => (
          <figure key={index}>
            {artwork.image && <img src={artwork.image} alt={artwork.title || `작품 ${index + 1}`} />}
            <figcaption>
              <b>{artwork.title || `작품 ${index + 1}`}</b>
              {artwork.artist && <span>{artwork.artist}</span>}
              {(artwork.year || artwork.medium) && (
                <span>{[artwork.year, artwork.medium].filter(Boolean).join(' · ')}</span>
              )}
              {(artwork.width || artwork.height) && (
                <span>
                  {[artwork.width, artwork.height, artwork.depth].filter(Boolean).join(' × ')}
                  {artwork.unit ? ` ${artwork.unit}` : ''}
                </span>
              )}
              {artwork.description && <p>{artwork.description}</p>}
              {artwork.price && <span className="content-price">{artwork.price}</span>}
            </figcaption>
          </figure>
        ))}
      </div>
      {artworks.length === 0 && !body.intro && (
        <p className="hint-text">아직 내용이 없는 컨텐츠예요.</p>
      )}
    </article>
  );
}
