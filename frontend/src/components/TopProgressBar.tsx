import React from 'react';

interface TopProgressBarProps {
  visible: boolean;
}

const TopProgressBar: React.FC<TopProgressBarProps> = ({ visible }) => {
  if (!visible) return null;

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        height: '3px',
        background: 'var(--m3-primary-container)',
        overflow: 'hidden',
        zIndex: 9999,
      }}
    >
      <div
        style={{
          height: '100%',
          width: '25%',
          background: 'var(--m3-primary)',
          borderRadius: '0 var(--m3-shape-full) var(--m3-shape-full) 0',
          animation: 'indeterminate 1.4s var(--m3-easing-standard) infinite',
        }}
      />
    </div>
  );
};

export default TopProgressBar;
