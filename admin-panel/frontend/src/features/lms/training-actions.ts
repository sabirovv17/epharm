/** null is the explicitly confirmed LEGACY contract; an empty managed map denies everything. */
export type TrainingActions = Readonly<Record<string, boolean>> | null

export function trainingAllowed(actions: TrainingActions, key: string, legacy: boolean): boolean {
  return actions == null ? legacy : actions[key] === true
}
