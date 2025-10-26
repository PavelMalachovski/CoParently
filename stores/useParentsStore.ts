import { create } from 'zustand';
import { persist, createJSONStorage, combine } from 'zustand/middleware';
import type { Parent } from '../types';

const defaultParents: Parent[] = [
  { id: 'mom', name: 'With Mom', color: 'bg-rose-200', textColor: 'text-rose-800' },
  { id: 'dad', name: 'With Dad', color: 'bg-sky-200', textColor: 'text-sky-800' },
];

export const useParentsStore = create(
  persist(
    combine(
      // Initial state
      { parents: defaultParents },
      // Actions
      (set) => ({
        addParent: (parent: Parent) => set((state) => ({
          parents: [...state.parents, parent]
        })),

        updateParent: (id: string, updates: Partial<Parent>) => set((state) => ({
          parents: state.parents.map(p =>
            p.id === id ? { ...p, ...updates } : p
          )
        })),

        deleteParent: (id: string) => set((state) => ({
          parents: state.parents.filter(p => p.id !== id)
        })),

        resetToDefault: () => set({ parents: defaultParents }),
      })
    ),
    {
      name: 'coparently-parents',
      storage: createJSONStorage(() => localStorage),
    }
  )
);


