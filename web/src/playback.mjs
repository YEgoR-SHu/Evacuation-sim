/** Interpolate only positions on the same floor. Stair transitions occur at the end. */
export function pointOnPath(path, fraction) {
    if (!path?.length) return null;
    if (path.length === 1 || fraction <= 0) return path[0];
    if (fraction >= 1) return path.at(-1);
    if (path.some(p => p.floorId !== path[0].floorId)) return path[0];
    const lengths = path.slice(1).map((p, i) => Math.hypot(p.x - path[i].x, p.y - path[i].y));
    const total = lengths.reduce((a, b) => a + b, 0);
    if (total === 0) return path[0];
    let remaining = total * fraction;
    for (let i = 0; i < lengths.length; i++) {
        const length = lengths[i];
        if (remaining <= length && length > 0) {
            const ratio = remaining / length;
            return { floorId: path[i].floorId, x: path[i].x + (path[i + 1].x - path[i].x) * ratio, y: path[i].y + (path[i + 1].y - path[i].y) * ratio };
        }
        remaining -= length;
    }
    return path.at(-1);
}

export function personAt(person, time, source = null) {
    const timeline = person.timeline || [];
    if (person.status === 'evacuated' && time >= person.finishSeconds) return { state: 'evacuated', position: null };
    let position = source || timeline.find(s => s.path?.length)?.path[0] || null;
    for (const segment of timeline) {
        if (time < segment.startSeconds) break;
        if (time < segment.endSeconds) {
            const fraction = (time - segment.startSeconds) / Math.max(1e-12, segment.endSeconds - segment.startSeconds);
            return { state: segment.kind, position: pointOnPath(segment.path, fraction) || position };
        }
        if (segment.path?.length) position = segment.path.at(-1);
    }
    return { state: person.status === 'unreachable' && time >= person.finishSeconds ? 'unreachable' : 'move', position };
}

export function playbackCounts(people, time) {
    let out = 0, unreachable = 0;
    for (const person of people) {
        if (time >= person.finishSeconds) {
            if (person.status === 'evacuated') out++;
            else unreachable++;
        }
    }
    return { out, unreachable, inside: people.length - out };
}
