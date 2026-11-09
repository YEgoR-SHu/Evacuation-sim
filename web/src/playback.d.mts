import type { Position, PersonResult } from './types';
export function pointOnPath(path: Position[], fraction: number): Position | null;
export function personAt(person: PersonResult, time: number, source?: Position | null): { state: string; position: Position | null };
export function playbackCounts(people: PersonResult[], time: number): { out: number; unreachable: number; inside: number };
