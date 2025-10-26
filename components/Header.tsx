
import React from 'react';
import { ChevronLeftIcon, ChevronRightIcon } from './IconComponents';

interface HeaderProps {
  month: string;
  year: number;
  onPrev: () => void;
  onNext: () => void;
  onToday: () => void;
}

export const Header: React.FC<HeaderProps> = ({ month, year, onPrev, onNext, onToday }) => {
  return (
    <header className="flex flex-col sm:flex-row items-center justify-between space-y-4 sm:space-y-0">
      <div className="flex items-center space-x-2">
        <div className="w-10 h-10 bg-gradient-to-br from-sky-400 to-rose-400 rounded-lg"></div>
        <h1 className="text-2xl sm:text-3xl font-bold text-gray-800 tracking-tight">
          CoParently
        </h1>
      </div>
      <div className="flex items-center space-x-2 sm:space-x-4 bg-white p-2 rounded-xl shadow-sm border border-gray-100">
         <button onClick={onPrev} className="p-2 rounded-md hover:bg-gray-100 text-gray-500 hover:text-gray-700 transition">
            <ChevronLeftIcon />
         </button>
         <h2 className="text-lg font-semibold text-gray-700 w-32 text-center">{month} {year}</h2>
         <button onClick={onNext} className="p-2 rounded-md hover:bg-gray-100 text-gray-500 hover:text-gray-700 transition">
            <ChevronRightIcon />
         </button>
         <button onClick={onToday} className="hidden sm:block px-4 py-2 text-sm font-semibold text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-md transition">
            Today
         </button>
      </div>
    </header>
  );
};
