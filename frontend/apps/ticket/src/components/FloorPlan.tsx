import { useEffect, useState, type ReactNode } from 'react';
import type { VenueFloor, VenuePoint, VenueShape } from '../ticket/api';

/** A floor is drawn on a plan this many units wide; its height follows the plan's image. */
export const PLAN_WIDTH = 1000;
export const DEFAULT_HEIGHT = 700;

/** The swatches an area can take, told apart by lightness as well as hue. */
export const AREA_COLORS: Array<[string, string]> = [
  ['#315cf4', '파랑'], ['#14855f', '초록'], ['#ad6500', '주황'], ['#bf3226', '빨강'],
  ['#6b3fc4', '보라'], ['#0b6f80', '청록'], ['#646c7e', '회색'],
];

export type Area = Extract<VenueShape, { type: 'area' }>;

export function centre(pts: VenuePoint[]): VenuePoint {
  return [pts.reduce((sum, p) => sum + p[0], 0) / pts.length, pts.reduce((sum, p) => sum + p[1], 0) / pts.length];
}

/** Whether a point lies inside an area, by counting how often a ray from it crosses the edge. */
export function inside(point: VenuePoint, pts: VenuePoint[]): boolean {
  let hit = false;
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const [xi, yi] = pts[i];
    const [xj, yj] = pts[j];
    if ((yi > point[1]) !== (yj > point[1]) && point[0] < ((xj - xi) * (point[1] - yi)) / (yj - yi) + xi) hit = !hit;
  }
  return hit;
}

/**
 * The plan's height in units. A floor saved since drawing came in says it; an older one
 * that only has an image takes the image's own proportions, so its pins stay where they
 * were put; a floor with neither is the default.
 */
export function useFloorHeight(floor: VenueFloor | undefined, imageUrl: string | null): number {
  return useFloorSize(floor, imageUrl).height;
}

/** The height, and whether it is settled rather than a default standing in while the image loads. */
export function useFloorSize(floor: VenueFloor | undefined, imageUrl: string | null): { height: number; settled: boolean } {
  const [natural, setNatural] = useState<number | null>(null);
  const known = floor?.height ?? null;
  useEffect(() => {
    setNatural(null);
    if (known !== null || !imageUrl) return;
    let current = true;
    const image = new Image();
    image.onload = () => {
      if (current && image.naturalWidth > 0) setNatural(Math.round((PLAN_WIDTH * image.naturalHeight) / image.naturalWidth));
    };
    image.src = imageUrl;
    return () => { current = false; };
  }, [known, imageUrl]);
  return { height: known ?? natural ?? DEFAULT_HEIGHT, settled: known !== null || natural !== null || !imageUrl };
}

/**
 * A floor as it is shown: the plan image if there is one, what was drawn over it, and
 * whatever the caller lays on top (pins) in percentages of the same box.
 */
export function FloorPlan({ floor, imageUrl, height, picked, onArea, children }: {
  floor: VenueFloor; imageUrl: string | null; height: number;
  picked?: string | null; onArea?: (area: Area) => void; children?: ReactNode;
}) {
  const shapes = floor.shapes ?? [];
  const areas = shapes.filter((s): s is Area => s.type === 'area');
  return (
    <div className="floor-plan" style={{ aspectRatio: `${PLAN_WIDTH} / ${height}` }}>
      {imageUrl && <img src={imageUrl} alt="" />}
      {shapes.length > 0 && (
        <svg viewBox={`0 0 ${PLAN_WIDTH} ${height}`} preserveAspectRatio="none" aria-hidden="true">
          <defs>
            <marker id="floor-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
              <path d="M0 0L10 5L0 10z" fill="#4b5470" />
            </marker>
          </defs>
          {areas.map(area => (
            <polygon key={area.id} points={area.pts.map(p => p.join(',')).join(' ')}
              fill={area.color} fillOpacity={area.id === picked ? 0.28 : 0.12}
              stroke={area.color} strokeWidth={area.id === picked ? 7 : 3} strokeLinejoin="round"
              className={onArea ? 'floor-area' : undefined} onClick={onArea ? () => onArea(area) : undefined} />
          ))}
          {areas.filter(area => area.showName && area.name).map(area => {
            const [x, y] = centre(area.pts);
            return <text key={area.id} x={x} y={y} className="floor-area-name" fill={area.color}>{area.name}</text>;
          })}
          {shapes.map(shape => shape.type === 'line' && (
            <line key={shape.id} x1={shape.pts[0][0]} y1={shape.pts[0][1]} x2={shape.pts[1][0]} y2={shape.pts[1][1]}
              stroke="#4b5470" strokeWidth={5} strokeDasharray="14 10" markerEnd="url(#floor-arrow)" />
          ))}
          {shapes.map(shape => shape.type === 'label' && (
            <text key={shape.id} x={shape.x} y={shape.y} className="floor-label">{shape.name}</text>
          ))}
        </svg>
      )}
      {children}
    </div>
  );
}
