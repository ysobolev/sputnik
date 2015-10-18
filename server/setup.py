#!/usr/bin/env python

from distutils.core import setup
from pkgutil import walk_packages

def find_packages():
    for _, name, ispkg in walk_packages("."):
        if ispkg:
            yield name

setup(name="Sputnik",
      version="1.3.0",
      description="Sputnik Exchange",
      author="Mimetic Markets",
      url="https://m2.io",
      packages=list(find_packages()),
      package_data={"sputnik.templates": ["*.html", "*.email"],
                    "sputnik.specs": ["objects/*.json", "rpc/*.json",
                                      "public/*.json"]}
     )
      
