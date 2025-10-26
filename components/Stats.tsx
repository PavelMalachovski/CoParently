import React, { useMemo } from 'react';
import { format } from 'date-fns';
import type { CalendarEvent, Parent } from '../types';
import { parseDateString, isInSameMonth } from '../utils/dateUtils';

interface StatsProps {
  events: CalendarEvent[];
  parents: Parent[];
  currentDate: Date;
}

export const Stats: React.FC<StatsProps> = ({ events, parents, currentDate }) => {
  const stats = useMemo(() => {
    const monthlyEvents = events.filter(event => {
      const eventDate = parseDateString(event.date);
      return isInSameMonth(eventDate, currentDate);
    });

    const counts = new Map<string, number>();
    parents.forEach(p => counts.set(p.id, 0));

    monthlyEvents.forEach(event => {
      counts.set(event.parentId, (counts.get(event.parentId) || 0) + 1);
    });

    const totalDays = monthlyEvents.length;

    return {
        counts,
        totalDays
    };

  }, [events, parents, currentDate]);

  const monthName = format(currentDate, 'MMMM');

  return (
    <div className="bg-white p-6 rounded-2xl shadow-sm h-full">
      <h3 className="text-xl font-bold text-gray-800 mb-1">Monthly Summary</h3>
      <p className="text-gray-500 text-sm mb-6">For {monthName}</p>

      <div className="space-y-4">
        {parents.map(parent => {
          const count = stats.counts.get(parent.id) || 0;
          const percentage = stats.totalDays > 0 ? (count / stats.totalDays) * 100 : 0;
          return (
            <div key={parent.id}>
              <div className="flex justify-between items-center mb-1">
                <span className={`font-semibold text-sm ${parent.textColor}`}>{parent.name}</span>
                <span className={`font-bold text-sm ${parent.textColor}`}>{count} days</span>
              </div>
              <div className="w-full bg-gray-200 rounded-full h-2.5">
                <div
                    className={`${parent.color} h-2.5 rounded-full transition-all duration-500 ease-out`}
                    style={{ width: `${percentage}%` }}
                ></div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
