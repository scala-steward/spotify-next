let
  nixpkgs = builtins.fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/20.09.tar.gz";
    sha256 = "1wg61h4gndm3vcprdcg7rc4s1v3jkm5xd7lw8r2f67w502y94gcy";
  };
  secrets = import ./secrets.nix;

  pkgs = import nixpkgs { };
in pkgs.mkShell {
  buildInputs = [ pkgs.netlify-cli pkgs.nodejs-14_x ];
  shellHook = "export NETLIFY_AUTH_TOKEN=${secrets.netlify.token}";
}
