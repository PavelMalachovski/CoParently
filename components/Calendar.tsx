import React from 'react';
import type { CalendarEvent, Parent } from '../types';

// FIX: Update CalendarProps to accept `days` and `getMonth` from the parent component.
// This resolves an error where the `useCalendar` hook was called with incorrect arguments.
interface CalendarProps {
  currentDate: Date;
  days: Date[];
  getMonth: () => number;
  events: CalendarEvent[];
  parents: Parent[];
  onDayClick: (date: Date) => void;
}

const DayOfWeekHeader: React.FC<{ day: string }> = ({ day }) => (
  <div className="text-center font-semibold text-gray-600 text-xs sm:text-sm tracking-wider uppercase">{day}</div>
);

interface DayCellProps {
    day: Date;
    isToday: boolean;
    isCurrentMonth: boolean;
    event: CalendarEvent | undefined;
    parent: Parent | undefined;
    onClick: (date: Date) => void;
}

const DayCell: React.FC<DayCellProps> = ({ day, isToday, isCurrentMonth, event, parent, onClick }) => {
    const dayCellClasses = [
        "relative flex flex-col h-24 sm:h-32 p-2 rounded-lg transition-all duration-200 ease-in-out cursor-pointer",
        isCurrentMonth ? "bg-white hover:bg-gray-100" : "bg-gray-50 text-gray-400 hover:bg-gray-100",
        isToday ? "border-2 border-indigo-500" : "border border-gray-100",
        event ? "" : ""
    ].join(" ");

    const dayNumberClasses = [
        "text-xs sm:text-sm font-semibold",
        isToday ? "text-indigo-600" : isCurrentMonth ? "text-gray-700" : "text-gray-400"
    ].join(" ");

    return (
        <div className={dayCellClasses} onClick={() => onClick(day)}>
            <span className={dayNumberClasses}>{day.getDate()}</span>
            {event && parent && (
                 <div className={`mt-1 p-1.5 rounded-md text-center text-xs sm:text-sm font-medium flex-grow flex items-center justify-center ${parent.color} ${parent.textColor}`}>
                    {parent.name}
                </div>
            )}
        </div>
    );
};


export const Calendar: React.FC<CalendarProps> = ({ currentDate, days, getMonth, events, parents, onDayClick }) => {
  const daysOfWeek = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

  // FIX: Explicitly type Maps to ensure TypeScript can correctly infer the value types.
  const eventsMap = new Map<string, CalendarEvent>(events.map(e => [e.date, e]));
  const parentsMap = new Map<string, Parent>(parents.map(p => [p.id, p]));

  return (
    <div>
      <div className="grid grid-cols-7 gap-2 mb-3">
        {daysOfWeek.map(day => <DayOfWeekHeader key={day} day={day} />)}
      </div>
      <div className="grid grid-cols-7 gap-2">
        {days.map((day, index) => {
          const dateString = day.toISOString().split('T')[0];
          const event = eventsMap.get(dateString);
          const parent = event ? parentsMap.get(event.parentId) : undefined;
          const isCurrentMonth = day.getMonth() === getMonth();
          
          const today = new Date();
          const isToday = day.getDate() === today.getDate() && day.getMonth() === today.getMonth() && day.getFullYear() === today.getFullYear();

          return (
            <DayCell
                key={index}
                day={day}
                isToday={isToday}
                isCurrentMonth={isCurrentMonth}
                event={event}
                parent={parent}
                onClick={onDayClick}
            />
          );
        })}
      </div>
    </div>
  );
};