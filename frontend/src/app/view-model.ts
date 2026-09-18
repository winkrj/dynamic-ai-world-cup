import type { Candidate, Preview, SharedBracket, Size } from '../api/types.ts';
import type { PlayState, currentMatch } from '../play/core.ts';

export interface UserProblem {
  title: string;
  detail: string;
  requestId?: string;
  retryAt?: number;
}

export interface ViewState {
  screen: 'input' | 'size' | 'generating' | 'preview' | 'play' | 'champion' | 'share-loading' | 'share' | 'share-error';
  prompt: string;
  size: Size;
  preview?: Preview;
  previewLocked: boolean;
  play?: PlayState;
  match?: ReturnType<typeof currentMatch>;
  champion?: Candidate;
  shared?: SharedBracket;
  shareUrl?: string;
  generationStatus?: 'QUEUED' | 'RUNNING';
  busy: boolean;
  saving: boolean;
  saved: boolean;
  canRetry: boolean;
  canEdit: boolean;
  storageBlocked: boolean;
  locked: boolean;
  synthetic: boolean;
  homeReturnsToExisting: boolean;
  now: number;
  error: UserProblem | null;
  notice: string | null;
}

export interface Actions {
  editPrompt(value: string): void;
  chooseSize(size: Size): void;
  next(): void;
  back(): void;
  generate(): void;
  regenerate(): void;
  start(): void;
  ready(sequence: number): void;
  choose(id: string): void;
  retry(): void;
  editInput(): void;
  newCup(): void;
  replay(): void;
  share(): void;
  copyShare(): void;
}
