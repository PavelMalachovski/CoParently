import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import type { Parent } from '../types';

interface ParentsState {
  parents: Parent[];
  addParent: (parent: Parent) => void;
  updateParent: (id: string, updates: Partial<Parent>) => void;
  deleteParent: (id: string) => void;
  resetToDefault: () => void;
}

const defaultParents: Parent[] = [
  { id: 'mom', name: 'With Mom', color: 'bg-rose-200', textColor: 'text-rose-800' },
  { id: 'dad', name: 'With Dad', color: 'bg-sky-200', textColor: 'text-sky-800' },
];

export const useParentsStore = create<ParentsState>()(
  persist(
    (set) => ({
      parents: defaultParents,

      addParent: (parent) => set((state) => ({
        parents: [...state.parents, parent]
      })),

      updateParent: (id, updates) => set((state) => ({
        parents: state.parents.map(p =>
          p.id === id ? { ...p, ...updates } : p
        )
      })),

      deleteParent: (id) => set((state) => ({
        parents: state.parents.filter(p => p.id !== id)
      })),

      resetToDefault: () => set({ parents: defaultParents }),
    }),
    {
      name: 'coparently-parents',
      storage: createJSONStorage(() => localStorage),
    }
  )
);


