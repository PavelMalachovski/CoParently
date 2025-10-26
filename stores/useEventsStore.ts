import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import type { CalendarEvent } from '../types';

interface EventsState {
  events: CalendarEvent[];
  addEvent: (event: CalendarEvent) => void;
  updateEvent: (date: string, parentId: string, note?: string) => void;
  deleteEvent: (date: string) => void;
  clearAllEvents: () => void;
  generateSchedule: (startDate: Date, pattern: 'week-on-week-off' | '2-2-3' | 'every-other-weekend', parent1Id: string, parent2Id: string, months: number) => void;
}

export const useEventsStore = create<EventsState>()(
  persist(
    (set, get) => ({
      events: [],

      addEvent: (event) => set((state) => ({
        events: [...state.events, event]
      })),

      updateEvent: (date, parentId, note) => set((state) => ({
        events: state.events.map(e =>
          e.date === date ? { date, parentId, note } : e
        )
      })),

      deleteEvent: (date) => set((state) => ({
        events: state.events.filter(e => e.date !== date)
      })),

      clearAllEvents: () => set({ events: [] }),

      generateSchedule: (startDate, pattern, parent1Id, parent2Id, months) => {
        const events: CalendarEvent[] = [];
        const endDate = new Date(startDate);
        endDate.setMonth(endDate.getMonth() + months);

        const getWeekNumber = (d: Date): number => {
          d = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
          d.setUTCDate(d.getUTCDate() + 4 - (d.getUTCDay() || 7));
          const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
          const weekNo = Math.ceil((((d.getTime() - yearStart.getTime()) / 86400000) + 1) / 7);
          return weekNo;
        };

        let currentDate = new Date(startDate);

        while (currentDate <= endDate) {
          const weekNumber = getWeekNumber(currentDate);
          let parentId: string;

          switch (pattern) {
            case 'week-on-week-off':
              parentId = weekNumber % 2 === 0 ? parent1Id : parent2Id;
              break;
            case 'every-other-weekend':
              const dayOfWeek = currentDate.getDay();
              const isWeekend = dayOfWeek === 0 || dayOfWeek === 6;
              if (isWeekend) {
                parentId = weekNumber % 2 === 0 ? parent1Id : parent2Id;
              } else {
                parentId = currentDate <= startDate ? parent1Id : parent2Id;
              }
              break;
            case '2-2-3':
              // 2 days parent 1, 2 days parent 2, 3 days parent 1, then repeat
              const daysSinceStart = Math.floor((currentDate.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24));
              const cycleDay = daysSinceStart % 7;
              parentId = (cycleDay < 2 || cycleDay >= 5) ? parent1Id : parent2Id;
              break;
            default:
              parentId = weekNumber % 2 === 0 ? parent1Id : parent2Id;
          }

          events.push({
            date: currentDate.toISOString().split('T')[0],
            parentId
          });

          currentDate = new Date(currentDate);
          currentDate.setDate(currentDate.getDate() + 1);
        }

        set({ events });
      }
    }),
    {
      name: 'coparently-events',
      storage: createJSONStorage(() => localStorage),
    }
  )
);

