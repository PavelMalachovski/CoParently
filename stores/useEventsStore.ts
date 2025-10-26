import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { addDays, differenceInDays } from 'date-fns';
import type { CalendarEvent } from '../types';
import { formatDateString, getWeekNumber, addMonthsToDate } from '../utils/dateUtils';

interface EventsState {
  events: CalendarEvent[];
  addEvent: (event: CalendarEvent) => void;
  updateEvent: (date: string, parentId: string, note?: string) => void;
  deleteEvent: (date: string) => void;
  clearAllEvents: () => void;
  generateSchedule: (startDate: Date, pattern: 'week-on-week-off' | '2-2-3' | 'every-other-weekend', parent1Id: string, parent2Id: string, months: number) => void;
  initializeDefaultSchedule: () => void;
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
        const endDate = addMonthsToDate(startDate, months);

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
              const daysSinceStart = differenceInDays(currentDate, startDate);
              const cycleDay = daysSinceStart % 7;
              parentId = (cycleDay < 2 || cycleDay >= 5) ? parent1Id : parent2Id;
              break;
            default:
              parentId = weekNumber % 2 === 0 ? parent1Id : parent2Id;
          }

          events.push({
            date: formatDateString(currentDate),
            parentId
          });

          currentDate = addDays(currentDate, 1);
        }

        set({ events });
      },

      initializeDefaultSchedule: () => {
        // Only initialize if events are empty
        const currentEvents = get().events;
        if (currentEvents.length > 0) return;

        const today = new Date();
        const events: CalendarEvent[] = [];

        // Generate for current, previous and next 2 months for better UX
        for (let m = -2; m <= 2; m++) {
          const monthStart = addMonthsToDate(new Date(today.getFullYear(), today.getMonth(), 1), m);
          const year = monthStart.getFullYear();
          const month = monthStart.getMonth();
          const daysInMonth = new Date(year, month + 1, 0).getDate();

          for (let i = 1; i <= daysInMonth; i++) {
            const date = new Date(year, month, i);
            const weekNumber = getWeekNumber(date);

            // A continuous week-on, week-off schedule
            const parentId = weekNumber % 2 === 0 ? 'mom' : 'dad';
            events.push({ date: formatDateString(date), parentId });
          }
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
