import { useMemo } from 'react';

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
    const year = currentDate.getFullYear();
    const month = currentDate.getMonth();

    const firstDayOfMonth = new Date(year, month, 1);
    const lastDayOfMonth = new Date(year, month + 1, 0);

    const firstDayOfWeek = firstDayOfMonth.getDay(); // 0 (Sun) - 6 (Sat)
    const daysInMonth = lastDayOfMonth.getDate();

    const calendarDays: Date[] = [];

    // Add days from previous month
    const prevMonthLastDay = new Date(year, month, 0).getDate();
    for (let i = firstDayOfWeek; i > 0; i--) {
      calendarDays.push(new Date(year, month - 1, prevMonthLastDay - i + 1));
    }

    // Add days from current month
    for (let i = 1; i <= daysInMonth; i++) {
      calendarDays.push(new Date(year, month, i));
    }

    // Add days from next month to fill the grid (to a total of 42 cells for 6 weeks)
    const remainingDays = 42 - calendarDays.length;
    for (let i = 1; i <= remainingDays; i++) {
      calendarDays.push(new Date(year, month + 1, i));
    }
    
    return calendarDays;
  }, [currentDate]);

  const goToNextMonth = () => {
    setCurrentDate(new Date(currentDate.getFullYear(), currentDate.getMonth() + 1, 1));
  };

  const goToPreviousMonth = () => {
    setCurrentDate(new Date(currentDate.getFullYear(), currentDate.getMonth() - 1, 1));
  };

  const goToToday = () => {
    setCurrentDate(new Date());
  }

  return {
    days,
    currentMonthName: currentDate.toLocaleString('default', { month: 'long' }),
    currentYear: currentDate.getFullYear(),
    getMonth: () => currentDate.getMonth(),
    getYear: () => currentDate.getFullYear(),
    goToNextMonth,
    goToPreviousMonth,
    goToToday,
  };
};