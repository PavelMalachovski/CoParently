import { useMemo } from 'react';
import {
  startOfMonth,
  endOfMonth,
  startOfWeek,
  endOfWeek,
  eachDayOfInterval,
  format,
  addMonths,
  subMonths,
  getMonth,
  getYear
} from 'date-fns';

/**
 * A custom hook to manage calendar logic.
 * It's a pure hook that calculates calendar data based on the state passed from its parent.
 * @param currentDate - The currently selected date to display the calendar for.
 * @param setCurrentDate - A function to update the current date.
 */
export const useCalendar = (
  currentDate: Date,
  setCurrentDate: (date: Date) => void
) => {

  const days = useMemo(() => {
    const monthStart = startOfMonth(currentDate);
    const monthEnd = endOfMonth(currentDate);

    // Get the start of the week for the first day of the month
    const calendarStart = startOfWeek(monthStart, { weekStartsOn: 0 }); // Sunday

    // Get the end of the week for the last day of the month
    const calendarEnd = endOfWeek(monthEnd, { weekStartsOn: 0 }); // Sunday

    // Get all days in the interval
    const calendarDays = eachDayOfInterval({
      start: calendarStart,
      end: calendarEnd
    });

    return calendarDays;
  }, [currentDate]);

  const goToNextMonth = () => {
    setCurrentDate(addMonths(startOfMonth(currentDate), 1));
  };

  const goToPreviousMonth = () => {
    setCurrentDate(subMonths(startOfMonth(currentDate), 1));
  };

  const goToToday = () => {
    setCurrentDate(new Date());
  }

  return {
    days,
    currentMonthName: format(currentDate, 'MMMM'),
    currentYear: getYear(currentDate),
    getMonth: () => getMonth(currentDate),
    getYear: () => getYear(currentDate),
    goToNextMonth,
    goToPreviousMonth,
    goToToday,
  };
};
