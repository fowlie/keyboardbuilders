import React from "react";
import { useAuth0 } from "@auth0/auth0-react";
import { Image, Loader } from 'semantic-ui-react';

const Profile = () => {
  const { user, isAuthenticated, isLoading } = useAuth0();

  if (isLoading) {
    return <Loader active inline='centered' />;
  }

  return (
    isAuthenticated && (
      <div style={{ display: 'flex', alignItems: 'center' }}>
        <Image src={user.picture} avatar />
        <span style={{ marginLeft: '0.5em' }}>{user.name}</span>
      </div>
    )
  );
};

export default Profile;