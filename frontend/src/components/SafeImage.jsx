import React from 'react';

// This component uses a native img tag instead of Semantic UI's Image component
// to avoid the React warning about defaultProps
const SafeImage = ({ 
  src = '', 
  alt = '', 
  className = '',
  style = {},
  ...otherProps 
}) => {
  // Default styles that mimic Semantic UI Image
  const defaultStyle = {
    display: 'block',
    maxWidth: '100%',
    ...style
  };

  return (
    <img
      src={src}
      alt={alt}
      className={className}
      style={defaultStyle}
      {...otherProps}
    />
  );
};

export default SafeImage; 