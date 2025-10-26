import { create } from 'zustand';
import { combine } from 'zustand/middleware';
import type { ToastType } from '../components/Toast';

interface Toast {
  id: string;
  message: string;
  type: ToastType;
}

export const useToastStore = create(
  combine(
    // Initial state
    { toasts: [] as Toast[] },
    // Actions
    (set) => ({
      addToast: (message: string, type: ToastType = 'info') => {
        const id = Date.now().toString() + Math.random().toString(36).substr(2, 9);
        set((state) => ({
          toasts: [...state.toasts, { id, message, type }]
        }));
        return id;
      },

      removeToast: (id: string) => {
        set((state) => ({
          toasts: state.toasts.filter((toast) => toast.id !== id)
        }));
      },

      clearAllToasts: () => {
        set({ toasts: [] });
      },

      // Convenience methods
      success: (message: string) => {
        const id = Date.now().toString() + Math.random().toString(36).substr(2, 9);
        set((state) => ({
          toasts: [...state.toasts, { id, message, type: 'success' as ToastType }]
        }));
        return id;
      },

      error: (message: string) => {
        const id = Date.now().toString() + Math.random().toString(36).substr(2, 9);
        set((state) => ({
          toasts: [...state.toasts, { id, message, type: 'error' as ToastType }]
        }));
        return id;
      },

      warning: (message: string) => {
        const id = Date.now().toString() + Math.random().toString(36).substr(2, 9);
        set((state) => ({
          toasts: [...state.toasts, { id, message, type: 'warning' as ToastType }]
        }));
        return id;
      },

      info: (message: string) => {
        const id = Date.now().toString() + Math.random().toString(36).substr(2, 9);
        set((state) => ({
          toasts: [...state.toasts, { id, message, type: 'info' as ToastType }]
        }));
        return id;
      },
    })
  )
);

