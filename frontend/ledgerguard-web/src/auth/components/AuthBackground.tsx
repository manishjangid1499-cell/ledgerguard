import React from 'react';
import { Box, keyframes } from '@mui/material';

const ambientFloatTeal = keyframes`
  0% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(-25px, 20px) scale(1.05);
  }
  100% {
    transform: translate(0, 0) scale(1);
  }
`;

const ambientFloatNavy = keyframes`
  0% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(25px, -20px) scale(1.05);
  }
  100% {
    transform: translate(0, 0) scale(1);
  }
`;

const flowPulse = keyframes`
  0% {
    stroke-dashoffset: 700;
  }
  100% {
    stroke-dashoffset: -700;
  }
`;

const floatGently = keyframes`
  0% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-5px);
  }
  100% {
    transform: translateY(0);
  }
`;

const moveNode = keyframes`
  0% {
    offset-distance: 0%;
    opacity: 0;
  }
  15% {
    opacity: 0.65;
  }
  85% {
    opacity: 0.65;
  }
  100% {
    offset-distance: 100%;
    opacity: 0;
  }
`;

export const AuthBackground: React.FC = () => {
  const upperPath = 'M 80,160 C 260,160 340,240 500,240 S 720,150 980,150 S 1220,220 1360,190';
  const lowerPath = 'M 90,730 C 270,730 390,660 550,660 S 830,750 1070,750 S 1270,680 1370,690';

  return (
    <Box
      aria-hidden="true"
      sx={{
        position: 'fixed',
        inset: 0,
        zIndex: 0,
        overflow: 'hidden',
        pointerEvents: 'none',
        bgcolor: '#f8fafc',
      }}
    >
      {/* Soft atmospheric radial gradients with slow, quiet breathing motion */}
      <Box
        sx={{
          position: 'absolute',
          top: '-15%',
          right: '-10%',
          width: '55vw',
          height: '55vw',
          maxWidth: 800,
          maxHeight: 800,
          borderRadius: '50%',
          background: 'radial-gradient(circle, rgba(0, 121, 107, 0.04) 0%, rgba(0, 121, 107, 0) 70%)',
          filter: 'blur(50px)',
          animation: `${ambientFloatTeal} 24s ease-in-out infinite alternate`,
          willChange: 'transform',
          '@media (prefers-reduced-motion: reduce)': {
            animation: 'none !important',
          },
        }}
      />
      <Box
        sx={{
          position: 'absolute',
          bottom: '-15%',
          left: '-10%',
          width: '60vw',
          height: '60vw',
          maxWidth: 850,
          maxHeight: 850,
          borderRadius: '50%',
          background: 'radial-gradient(circle, rgba(15, 41, 66, 0.035) 0%, rgba(15, 41, 66, 0) 70%)',
          filter: 'blur(60px)',
          animation: `${ambientFloatNavy} 28s ease-in-out infinite alternate`,
          willChange: 'transform',
          '@media (prefers-reduced-motion: reduce)': {
            animation: 'none !important',
          },
        }}
      />

      {/* Whisper-quiet, ultra-subtle geometric grid overlay */}
      <Box
        sx={{
          position: 'absolute',
          inset: 0,
          backgroundImage:
            'linear-gradient(to right, rgba(15, 41, 66, 0.008) 1px, transparent 1px), linear-gradient(to bottom, rgba(15, 41, 66, 0.008) 1px, transparent 1px)',
          backgroundSize: '48px 48px',
          maskImage: 'radial-gradient(ellipse at 50% 50%, black 40%, transparent 80%)',
          WebkitMaskImage: 'radial-gradient(ellipse at 50% 50%, black 40%, transparent 80%)',
        }}
      />

      {/* Financial-Flow SVG Landscape */}
      <Box
        component="svg"
        viewBox="0 0 1440 900"
        preserveAspectRatio="xMidYMid slice"
        sx={{
          position: 'absolute',
          inset: 0,
          width: '100%',
          height: '100%',
          pointerEvents: 'none',
        }}
      >
        <defs>
          <linearGradient id="flowGrad1" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="#00796b" stopOpacity="0.22" />
            <stop offset="40%" stopColor="#0f2942" stopOpacity="0.10" />
            <stop offset="100%" stopColor="#00796b" stopOpacity="0.18" />
          </linearGradient>

          <linearGradient id="flowGrad2" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="#0f2942" stopOpacity="0.12" />
            <stop offset="50%" stopColor="#00796b" stopOpacity="0.16" />
            <stop offset="100%" stopColor="#0f2942" stopOpacity="0.08" />
          </linearGradient>

          <linearGradient id="pulseGrad" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="#00796b" stopOpacity="0" />
            <stop offset="50%" stopColor="#009688" stopOpacity="0.45" />
            <stop offset="100%" stopColor="#00796b" stopOpacity="0" />
          </linearGradient>
        </defs>

        {/* 1. Base Flow Lines */}
        <path
          d={upperPath}
          fill="none"
          stroke="url(#flowGrad1)"
          strokeWidth="1.2"
          strokeDasharray="4 4"
        />
        <path
          d={lowerPath}
          fill="none"
          stroke="url(#flowGrad2)"
          strokeWidth="1.2"
          strokeDasharray="4 4"
        />

        {/* 2. Flowing Animated Pulse along Upper Path */}
        <Box
          component="path"
          d={upperPath}
          fill="none"
          stroke="url(#pulseGrad)"
          strokeWidth="2.5"
          strokeDasharray="100 600"
          sx={{
            animation: `${flowPulse} 9s linear infinite`,
            '@media (prefers-reduced-motion: reduce)': {
              animation: 'none !important',
            },
          }}
        />

        {/* 3. Small Animated Teal Node moving along Upper Flow */}
        <Box
          component="circle"
          r="3"
          fill="#00796b"
          sx={{
            offsetPath: `path("${upperPath}")`,
            animation: `${moveNode} 9s cubic-bezier(0.4, 0, 0.2, 1) infinite`,
            filter: 'drop-shadow(0 0 4px rgba(0, 121, 107, 0.4))',
            '@media (prefers-reduced-motion: reduce)': {
              animation: 'none !important',
              display: 'none',
            },
          }}
        />

        {/* 4. Subtle Stationary Milestone Nodes */}
        {/* Upper nodes */}
        <circle cx="80" cy="160" r="5" fill="none" stroke="rgba(0, 121, 107, 0.18)" strokeWidth="1" />
        <circle cx="80" cy="160" r="2" fill="rgba(0, 121, 107, 0.35)" />

        <circle cx="500" cy="240" r="2.5" fill="rgba(15, 41, 66, 0.2)" />

        <circle cx="980" cy="150" r="5" fill="none" stroke="rgba(15, 41, 66, 0.12)" strokeWidth="1" />
        <circle cx="980" cy="150" r="2" fill="rgba(15, 41, 66, 0.3)" />

        <circle cx="1360" cy="190" r="2.5" fill="rgba(0, 121, 107, 0.25)" />

        {/* Lower nodes */}
        <circle cx="90" cy="730" r="2.5" fill="rgba(15, 41, 66, 0.25)" />

        <circle cx="550" cy="660" r="5" fill="none" stroke="rgba(0, 121, 107, 0.18)" strokeWidth="1" />
        <circle cx="550" cy="660" r="2" fill="rgba(0, 121, 107, 0.35)" />

        <circle cx="1070" cy="750" r="2.5" fill="rgba(15, 41, 66, 0.2)" />

        <circle cx="1370" cy="690" r="5" fill="none" stroke="rgba(0, 121, 107, 0.15)" strokeWidth="1" />
        <circle cx="1370" cy="690" r="2" fill="rgba(0, 121, 107, 0.3)" />

        {/* 5. Desktop-Only Subtle Background Financial Objects (Abstract Art, Zero Text) */}
        {/* Object 1: Translucent Ledger Document (Top-Right) */}
        <Box
          component="g"
          sx={{
            display: { xs: 'none', lg: 'block' },
            animation: `${floatGently} 18s ease-in-out infinite alternate`,
            '@media (prefers-reduced-motion: reduce)': {
              animation: 'none !important',
            },
          }}
        >
          <rect
            x="1080"
            y="170"
            width="130"
            height="160"
            rx="10"
            fill="rgba(255, 255, 255, 0.6)"
            stroke="rgba(15, 41, 66, 0.07)"
            strokeWidth="1"
          />
          {/* Faint ledger rows */}
          <rect x="1098" y="194" width="38" height="5" rx="2" fill="rgba(0, 121, 107, 0.18)" />
          <rect x="1098" y="214" width="76" height="4" rx="2" fill="rgba(15, 41, 66, 0.08)" />
          <rect x="1098" y="228" width="62" height="4" rx="2" fill="rgba(15, 41, 66, 0.08)" />
          <rect x="1098" y="242" width="82" height="4" rx="2" fill="rgba(15, 41, 66, 0.08)" />
          <rect x="1098" y="262" width="48" height="4" rx="2" fill="rgba(0, 121, 107, 0.12)" />
          <rect x="1098" y="276" width="70" height="4" rx="2" fill="rgba(15, 41, 66, 0.06)" />
        </Box>

        {/* Object 2: Subtle Shield Contour (Mid-Left) */}
        <Box
          component="g"
          sx={{
            display: { xs: 'none', lg: 'block' },
            animation: `${floatGently} 22s ease-in-out infinite alternate`,
            animationDelay: '-6s',
            '@media (prefers-reduced-motion: reduce)': {
              animation: 'none !important',
            },
          }}
        >
          <path
            d="M 170,390 Q 210,380 250,390 C 250,445 210,480 210,490 C 210,480 170,445 170,390 Z"
            fill="rgba(255, 255, 255, 0.55)"
            stroke="rgba(15, 41, 66, 0.07)"
            strokeWidth="1.2"
          />
          {/* Subtle shield core line */}
          <path
            d="M 210,400 L 210,475"
            stroke="rgba(0, 121, 107, 0.14)"
            strokeWidth="1.2"
            strokeDasharray="2 3"
          />
        </Box>

        {/* Object 3: Minimal Balance / Metric Card (Bottom-Left) */}
        <Box
          component="g"
          sx={{
            display: { xs: 'none', lg: 'block' },
            animation: `${floatGently} 20s ease-in-out infinite alternate`,
            animationDelay: '-11s',
            '@media (prefers-reduced-motion: reduce)': {
              animation: 'none !important',
            },
          }}
        >
          <rect
            x="210"
            y="640"
            width="120"
            height="80"
            rx="8"
            fill="rgba(255, 255, 255, 0.55)"
            stroke="rgba(15, 41, 66, 0.07)"
            strokeWidth="1"
          />
          {/* Minimal financial metric bars */}
          <rect x="232" y="678" width="6" height="22" rx="2" fill="rgba(15, 41, 66, 0.09)" />
          <rect x="248" y="666" width="6" height="34" rx="2" fill="rgba(0, 121, 107, 0.18)" />
          <rect x="264" y="672" width="6" height="28" rx="2" fill="rgba(15, 41, 66, 0.09)" />
          <rect x="280" y="660" width="6" height="40" rx="2" fill="rgba(0, 121, 107, 0.22)" />
          <line x1="226" y1="705" x2="294" y2="705" stroke="rgba(15, 41, 66, 0.08)" strokeWidth="1" />
        </Box>
      </Box>
    </Box>
  );
};
