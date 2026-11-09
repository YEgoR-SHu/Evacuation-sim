import test from 'node:test';
import assert from 'node:assert/strict';
import { pointOnPath, personAt, playbackCounts } from '../src/playback.mjs';
const p = (x, y, floorId = '1') => ({ x, y, floorId });
test('distance interpolation respects corners and unequal segments', () => {
    assert.deepEqual(pointOnPath([p(0, 0), p(10, 0), p(10, 30)], 0.5), p(10, 10));
});
test('stairs never interpolate between different floor coordinate systems', () => {
    const path = [p(10, 10, '2'), p(100, 100, '1')];
    assert.deepEqual(pointOnPath(path, 0.99), path[0]);
    assert.deepEqual(pointOnPath(path, 1), path[1]);
});
test('wait, exit boundary, seek backwards are deterministic', () => {
    const person = { status: 'evacuated', finishSeconds: 12, timeline: [
        { kind: 'move', startSeconds: 0, endSeconds: 10, path: [p(0, 0), p(10, 0)] },
        { kind: 'wait', startSeconds: 10, endSeconds: 12, path: [p(10, 0)] },
    ] };
    assert.deepEqual(personAt(person, 11), { state: 'wait', position: p(10, 0) });
    assert.deepEqual(personAt(person, 12), { state: 'evacuated', position: null });
    assert.deepEqual(personAt(person, 5), { state: 'move', position: p(5, 0) });
});
test('unreachable people remain visible and inside statistics', () => {
    const person = { status: 'unreachable', finishSeconds: 0, timeline: [] };
    assert.deepEqual(personAt(person, 50, p(3, 4)), { state: 'unreachable', position: p(3, 4) });
    assert.deepEqual(playbackCounts([person, { status: 'evacuated', finishSeconds: 10 }], 11), { out: 1, unreachable: 1, inside: 1 });
});
